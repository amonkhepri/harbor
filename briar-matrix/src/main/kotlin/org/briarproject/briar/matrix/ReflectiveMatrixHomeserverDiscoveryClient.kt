package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_CREDENTIALS
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.LOGIN_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.briarproject.briar.api.connector.ConnectorMessageType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Production [MatrixHomeserverDiscoveryClient], [MatrixLoginClient], [MatrixRoomSnapshotClient],
 * and [MatrixMessageSnapshotClient] that invoke the pinned
 * `org.matrix.rustcomponents:sdk-android` `ClientBuilder.serverName(...).build()`
 * discovery path entirely over reflection, so `:briar-matrix` keeps zero
 * compile-time dependency on the SDK (see this module's `build.gradle` and
 * [MatrixHomeserverDiscoveryFailureMapper]'s docstring). Mirrors
 * `ReflectiveTelegramTdlibMessageClient`'s isolation approach for TDLib.
 * `build()` is a Kotlin suspend function; [buildClient] bridges its
 * `Continuation`-based bytecode signature to a blocking call so [discover]
 * itself stays synchronous per the interface contract. Discovery uses only a
 * temporary client; login retains its client until logout or close.
 *
 * Persistent login uses the pinned AAR's real shape:
 * `ClientBuilder.sqliteStore(SqliteStoreBuilder(dataPath, cachePath).passphrase(...))`,
 * not the nonexistent `ClientBuilder.sqlitePath`/`ClientBuilder.passphrase`.
 * `Client.session()` is non-nullable and only meaningful right after a
 * successful `login()`, so a fresh instance cannot detect a prior login by
 * calling it; instead a successful [login] serializes the SDK's `Session`
 * fields, AES-256-GCM-encrypts them under the store's own encryption key, and
 * writes them atomically (temp file + rename) into a sidecar file inside the
 * store directory. [restore] decrypts that sidecar and feeds the `Session`
 * back through the explicit `Client.restoreSession(Session, Continuation)`
 * API on a freshly built client pointed at the same store. Both [login] and
 * [restore] also install a direct `ClientSessionDelegate` (via
 * `ClientBuilder.setSessionDelegate`), so a token refresh
 * the SDK performs internally after either call drives the delegate's
 * `saveSessionInKeychain` callback and rewrites the same encrypted sidecar,
 * keeping persisted tokens current instead of frozen at login/restore time.
 */
class ReflectiveMatrixHomeserverDiscoveryClient(
	private val discoveryTimeoutMs: Long = 30_000L,
	private val sessionDelegateFactory: MatrixClientSessionDelegateFactory? = null,
) : MatrixHomeserverDiscoveryClient,
	MatrixLoginClient,
	MatrixRoomSnapshotClient,
	MatrixMessageSnapshotClient {

	private var loginClient: Any? = null

	override fun discover(serverName: String): DiscoveryResult {
		// `serverName(...)` and `inMemoryStore()` each return their own
		// AutoCloseable-backed `ClientBuilder` wrapper on the pinned SDK rather
		// than mutating the receiver in place, so every distinct wrapper the
		// chain produces is tracked here and closed once discovery is done.
		val builders = ArrayList<Any>(3)
		var client: Any? = null
		return try {
			val builderClass = Class.forName(CLIENT_BUILDER_CLASS_NAME)
			var builder = track(builders, builderClass.getConstructor().newInstance())
			builder = track(
				builders,
				builderClass.getMethod("serverName", String::class.java).invoke(builder, serverName),
			)
			builder = track(builders, builderClass.getMethod("inMemoryStore").invoke(builder))
			client = buildClient(builder, builderClass)
			DiscoveryResult.Resolved(readHomeserverUrl(client))
		} catch (e: DiscoveryAdapterFailure) {
			DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED)
		} catch (e: InvocationTargetException) {
			DiscoveryResult.Failed(mapThrowable(e.targetException ?: e))
		} catch (e: ReflectiveOperationException) {
			DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED)
		} catch (e: LinkageError) {
			DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED)
		} catch (e: Exception) {
			// The SDK's ClientBuildException subtypes surface here, either rethrown
			// synchronously by build() or delivered via the async Continuation below.
			DiscoveryResult.Failed(mapThrowable(e))
		} finally {
			closeQuietly(client)
			for (i in builders.indices.reversed()) closeQuietly(builders[i])
		}
	}

	@Synchronized
	override fun login(
		homeserverUrl: String,
		username: String,
		password: String,
		storeConfiguration: MatrixStoreConfiguration?,
	): RecoverableErrorDetail {
		val builders = ArrayList<Any>(4)
		var client: Any? = null
		return try {
			val builderClass = Class.forName(CLIENT_BUILDER_CLASS_NAME)
			var builder = track(builders, builderClass.getConstructor().newInstance())
			builder = track(
				builders,
				builderClass.getMethod("homeserverUrl", String::class.java).invoke(builder, homeserverUrl),
			)
			builder = track(builders, applyStore(builders, builder, builderClass, storeConfiguration))
			if (storeConfiguration != null) {
				builder = track(builders, installSessionDelegate(builder, builderClass, storeConfiguration))
			}
			client = buildClient(builder, builderClass)
			invokeUnit(
				client,
				"login",
				arrayOf(String::class.java, String::class.java, String::class.java, String::class.java),
				arrayOf(username, password, DEVICE_NAME, null),
			)
			if (storeConfiguration != null) persistSession(client, storeConfiguration)
			close()
			loginClient = client
			client = null
			NONE
		} catch (e: InvocationTargetException) {
			mapLoginFailure(e.targetException ?: e)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			LOGIN_FAILED
		} catch (e: ReflectiveOperationException) {
			LOGIN_FAILED
		} catch (e: LinkageError) {
			LOGIN_FAILED
		} catch (e: Exception) {
			mapLoginFailure(e)
		} finally {
			closeQuietly(client)
			for (i in builders.indices.reversed()) closeQuietly(builders[i])
		}
	}

	/**
	 * Reads the `Session` persisted by a prior [login] into [storeConfiguration]'s
	 * sidecar file and feeds it back through `Client.restoreSession(Session,
	 * Continuation)` on a client freshly built against the same store. A missing
	 * sidecar file yields [MatrixLoginClient.RestoreResult.NotFound], not a
	 * failure; the store itself is not opened in that case.
	 */
	@Synchronized
	override fun restore(
		storeConfiguration: MatrixStoreConfiguration,
	): MatrixLoginClient.RestoreResult {
		val file = sessionFile(storeConfiguration)
		if (!file.isFile) return MatrixLoginClient.RestoreResult.NotFound
		val builders = ArrayList<Any>(4)
		var client: Any? = null
		return try {
			val sessionClass = Class.forName(SESSION_CLASS_NAME)
			val session = readSessionFile(file, sessionClass, storeConfiguration)
			val builderClass = Class.forName(CLIENT_BUILDER_CLASS_NAME)
			var builder = track(builders, builderClass.getConstructor().newInstance())
			builder = track(
				builders,
				builderClass.getMethod("homeserverUrl", String::class.java)
					.invoke(builder, sessionClass.getMethod("getHomeserverUrl").invoke(session)),
			)
			builder = track(builders, applyStore(builders, builder, builderClass, storeConfiguration))
			builder = track(builders, installSessionDelegate(builder, builderClass, storeConfiguration))
			client = buildClient(builder, builderClass)
			invokeUnit(client, "restoreSession", arrayOf(sessionClass), arrayOf(session))
			val homeserverUrl = readHomeserverUrl(client)
			loginClient = client
			client = null
			MatrixLoginClient.RestoreResult.Restored(homeserverUrl)
		} catch (e: DiscoveryAdapterFailure) {
			MatrixLoginClient.RestoreResult.NotFound
		} catch (e: InvocationTargetException) {
			MatrixLoginClient.RestoreResult.Failed(mapThrowable(e.targetException ?: e))
		} catch (e: ReflectiveOperationException) {
			MatrixLoginClient.RestoreResult.NotFound
		} catch (e: LinkageError) {
			MatrixLoginClient.RestoreResult.NotFound
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			MatrixLoginClient.RestoreResult.NotFound
		} catch (e: IOException) {
			MatrixLoginClient.RestoreResult.NotFound
		} finally {
			closeQuietly(client)
			for (i in builders.indices.reversed()) closeQuietly(builders[i])
		}
	}

	/**
	 * Applies [storeConfiguration] to [builder] via the real
	 * `ClientBuilder.sqliteStore(SqliteStoreBuilder)` API, tracking every closeable
	 * wrapper the chain returns.
	 */
	private fun applyStore(
		builders: MutableList<Any>,
		builder: Any,
		builderClass: Class<*>,
		storeConfiguration: MatrixStoreConfiguration?,
	): Any {
		if (storeConfiguration == null) {
			return builderClass.getMethod("inMemoryStore").invoke(builder)
		}
		val storeBuilderClass = Class.forName(SQLITE_STORE_BUILDER_CLASS_NAME)
		var storeBuilder = track(
			builders,
			storeBuilderClass.getConstructor(String::class.java, String::class.java)
				.newInstance(sqliteDataPath(storeConfiguration), sqliteCachePath(storeConfiguration)),
		)
		storeBuilder = track(
			builders,
			storeBuilderClass.getMethod("passphrase", String::class.java)
				.invoke(storeBuilder, encodeHex(storeConfiguration.copyEncryptionKey())),
		)
		return builderClass.getMethod("sqliteStore", storeBuilderClass).invoke(builder, storeBuilder)
	}

	private fun sqliteDataPath(storeConfiguration: MatrixStoreConfiguration): String =
		File(storeConfiguration.directory, "data").apply { mkdirs() }.absolutePath

	private fun sqliteCachePath(storeConfiguration: MatrixStoreConfiguration): String =
		File(storeConfiguration.directory, "cache").apply { mkdirs() }.absolutePath

	private fun encodeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

	/** Reads [client]'s post-login `Session` and persists it to [storeConfiguration]'s sidecar file. */
	private fun persistSession(client: Any, storeConfiguration: MatrixStoreConfiguration) {
		val session = client.javaClass.getMethod("session").invoke(client)
		writeSessionFile(sessionFile(storeConfiguration), session, storeConfiguration)
	}

	/**
	 * Installs a direct `ClientSessionDelegate` on [builder] via the pinned SDK's
	 * `ClientBuilder.setSessionDelegate(ClientSessionDelegate)` so a later internal token
	 * refresh routes through `saveSessionInKeychain` and lands in the same encrypted sidecar
	 * [login]/[restore] write, instead of only ever persisting the token captured at login time.
	 */
	private fun installSessionDelegate(
		builder: Any,
		builderClass: Class<*>,
		storeConfiguration: MatrixStoreConfiguration,
	): Any {
		val delegateClass = Class.forName(SESSION_DELEGATE_CLASS_NAME)
		val factory = sessionDelegateFactory ?: Class.forName(SESSION_DELEGATE_FACTORY_CLASS_NAME)
			.getConstructor()
			.newInstance() as MatrixClientSessionDelegateFactory
		val delegate = factory.create(
			saveSession = { session ->
				writeSessionFile(sessionFile(storeConfiguration), session, storeConfiguration)
			},
			retrieveSession = { readSessionFileOrThrow(storeConfiguration) },
		)
		return builderClass.getMethod("setSessionDelegate", delegateClass).invoke(builder, delegate)
	}

	private fun readSessionFileOrThrow(storeConfiguration: MatrixStoreConfiguration): Any {
		val file = sessionFile(storeConfiguration)
		if (!file.isFile) throw IOException("no persisted Matrix session")
		return try {
			readSessionFile(file, Class.forName(SESSION_CLASS_NAME), storeConfiguration)
		} catch (e: IOException) {
			throw IOException("persisted Matrix session sidecar could not be read", e)
		} catch (e: ReflectiveOperationException) {
			throw IOException("persisted Matrix session sidecar could not be reconstructed", e)
		}
	}

	/**
	 * Serializes an SDK `Session`'s fields via its public getters, AES-256-GCM-encrypts them
	 * under [storeConfiguration]'s own encryption key, and writes the result atomically (temp
	 * file in the same directory + [File.renameTo]) so a crash or concurrent
	 * `saveSessionInKeychain` callback can never observe a partially written or plaintext file.
	 * Never logs field values.
	 */
	private fun writeSessionFile(
		file: File,
		session: Any,
		storeConfiguration: MatrixStoreConfiguration,
	) {
		val sessionClass = session.javaClass
		val accessToken = sessionClass.getMethod("getAccessToken").invoke(session) as String
		val refreshToken = sessionClass.getMethod("getRefreshToken").invoke(session) as String?
		val userId = sessionClass.getMethod("getUserId").invoke(session) as String
		val deviceId = sessionClass.getMethod("getDeviceId").invoke(session) as String
		val homeserverUrl = sessionClass.getMethod("getHomeserverUrl").invoke(session) as String
		val oauthData = sessionClass.getMethod("getOauthData").invoke(session) as String?
		val slidingSyncVersion = sessionClass.getMethod("getSlidingSyncVersion").invoke(session)
		val slidingSyncVersionName =
			slidingSyncVersion.javaClass.getMethod("name").invoke(slidingSyncVersion) as String
		val plaintext = ByteArrayOutputStream().also { buffer ->
			DataOutputStream(buffer).use { out ->
				out.writeUTF(accessToken)
				writeNullableUtf(out, refreshToken)
				out.writeUTF(userId)
				out.writeUTF(deviceId)
				out.writeUTF(homeserverUrl)
				writeNullableUtf(out, oauthData)
				out.writeUTF(slidingSyncVersionName)
			}
		}.toByteArray()
		writeEncryptedAtomic(file, plaintext, storeConfiguration)
	}

	private fun writeNullableUtf(out: DataOutputStream, value: String?) {
		out.writeBoolean(value != null)
		if (value != null) out.writeUTF(value)
	}

	private fun writeEncryptedAtomic(
		file: File,
		plaintext: ByteArray,
		storeConfiguration: MatrixStoreConfiguration,
	) {
		val key = storeConfiguration.copyEncryptionKey()
		try {
			val nonce = ByteArray(GCM_NONCE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
			val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
			cipher.init(
				Cipher.ENCRYPT_MODE,
				SecretKeySpec(key, "AES"),
				GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
			)
			val ciphertext = cipher.doFinal(plaintext)
			file.parentFile?.mkdirs()
			val tempFile = File.createTempFile("session-", ".tmp", file.parentFile)
			try {
				tempFile.setReadable(false, false)
				tempFile.setWritable(false, false)
				tempFile.setReadable(true, true)
				tempFile.setWritable(true, true)
				tempFile.outputStream().use { out ->
					out.write(nonce)
					out.write(ciphertext)
				}
				// File.renameTo (POSIX rename(2) on Android/Linux) atomically replaces an
				// existing same-directory target, unlike java.nio.file.Files.move/ATOMIC_MOVE,
				// which the Android SDK marks available only since API 26; Harbor's minSdk is
				// 21 and briar-android's coreLibraryDesugaring has no dependencies to backport it.
				if (!tempFile.renameTo(file)) {
					throw IOException("failed to atomically replace session sidecar")
				}
			} finally {
				tempFile.delete()
			}
		} finally {
			key.fill(0)
		}
	}

	/** Reconstructs an SDK `Session` reflectively from [file], matching [writeSessionFile]'s layout. */
	private fun readSessionFile(
		file: File,
		sessionClass: Class<*>,
		storeConfiguration: MatrixStoreConfiguration,
	): Any {
		val plaintext = readDecrypted(file, storeConfiguration)
		return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
			val accessToken = input.readUTF()
			val refreshToken = readNullableUtf(input)
			val userId = input.readUTF()
			val deviceId = input.readUTF()
			val homeserverUrl = input.readUTF()
			val oauthData = readNullableUtf(input)
			val slidingSyncVersionName = input.readUTF()
			val slidingSyncVersionClass = sessionClass.getMethod("getSlidingSyncVersion").returnType
			val slidingSyncVersion = slidingSyncVersionClass.getMethod("valueOf", String::class.java)
				.invoke(null, slidingSyncVersionName)
			sessionClass.getConstructor(
				String::class.java,
				String::class.java,
				String::class.java,
				String::class.java,
				String::class.java,
				String::class.java,
				slidingSyncVersionClass,
			).newInstance(
				accessToken,
				refreshToken,
				userId,
				deviceId,
				homeserverUrl,
				oauthData,
				slidingSyncVersion,
			)
		}
	}

	private fun readDecrypted(file: File, storeConfiguration: MatrixStoreConfiguration): ByteArray {
		val bytes = file.readBytes()
		if (bytes.size <= GCM_NONCE_LENGTH_BYTES) throw IOException("session sidecar too short")
		val nonce = bytes.copyOfRange(0, GCM_NONCE_LENGTH_BYTES)
		val ciphertext = bytes.copyOfRange(GCM_NONCE_LENGTH_BYTES, bytes.size)
		val key = storeConfiguration.copyEncryptionKey()
		return try {
			val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
			cipher.init(
				Cipher.DECRYPT_MODE,
				SecretKeySpec(key, "AES"),
				GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
			)
			cipher.doFinal(ciphertext)
		} catch (e: GeneralSecurityException) {
			// Wrong/rotated key, truncated ciphertext, or a tampered/corrupt sidecar all surface
			// here (including AEADBadTagException); restore()/readSessionFileOrThrow() only
			// handle IOException, not the wider GeneralSecurityException hierarchy.
			throw IOException("session sidecar failed to decrypt", e)
		} finally {
			key.fill(0)
		}
	}

	private fun readNullableUtf(input: DataInputStream): String? =
		if (input.readBoolean()) input.readUTF() else null

	private fun sessionFile(storeConfiguration: MatrixStoreConfiguration): File =
		File(storeConfiguration.directory, "session")

	/**
	 * Reads the retained client's joined, non-space rooms via the pinned AAR's
	 * synchronous `Client.rooms(): List<Room>`, filters to
	 * `Membership.JOINED`, sorts by room id for deterministic ordering (no
	 * timeline data is read at this slice), and closes every `Room` handle
	 * `rooms()` returns regardless of whether [limit] keeps it. Never logs
	 * room ids or names.
	 */
	@Synchronized
	override fun getJoinedRooms(limit: Int): List<MatrixRoomSnapshot> {
		val client = loginClient ?: return emptyList()
		var rooms = emptyList<Any>()
		return try {
			@Suppress("UNCHECKED_CAST")
			rooms = client.javaClass.getMethod("rooms").invoke(client) as List<Any>
			rooms.mapNotNull(::toJoinedRoomSnapshot)
				.sortedBy { it.roomId }
				.take(limit.coerceAtLeast(0))
		} catch (e: ClassCastException) {
			emptyList()
		} catch (e: InvocationTargetException) {
			emptyList()
		} catch (e: ReflectiveOperationException) {
			emptyList()
		} catch (e: LinkageError) {
			emptyList()
		} finally {
			rooms.forEach(::closeQuietly)
		}
	}

	private fun toJoinedRoomSnapshot(room: Any): MatrixRoomSnapshot? = try {
		val roomClass = room.javaClass
		val isSpace = roomClass.getMethod("isSpace").invoke(room) as Boolean
		val membership = roomClass.getMethod("membership").invoke(room)
		val isJoined = (membership as? Enum<*>)?.name == "JOINED"
		if (isSpace || !isJoined) {
			null
		} else {
			val id = roomClass.getMethod("id").invoke(room) as String
			val displayName = (roomClass.getMethod("displayName").invoke(room) as? String)
				?.takeIf { it.isNotEmpty() } ?: id
			MatrixRoomSnapshot(id, displayName)
		}
	} catch (e: InvocationTargetException) {
		null
	} catch (e: ReflectiveOperationException) {
		null
	} catch (e: LinkageError) {
		null
	}

	/**
	 * Reads a bounded timeline snapshot from the retained client, requesting at most one backward
	 * page when the initial mapped snapshot is shorter than [limit]. Timeline listeners own the
	 * delivered diffs, so each callback maps all plain fields before destroying its diffs; the
	 * retained SDK client is never closed here.
	 */
	@Synchronized
	override fun getRecentMessages(roomId: String, limit: Int): List<MatrixMessageSnapshot>? {
		if (limit <= 0) return emptyList()
		val client = loginClient ?: return null
		var room: Any? = null
		var timeline: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			val loadedTimeline =
				invokeCloseableValue(room, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
			timeline = loadedTimeline
			val listenerClass = Class.forName(TIMELINE_LISTENER_CLASS_NAME)
			val initialSnapshot = readTimelineSnapshot(loadedTimeline, listenerClass)
			if (initialSnapshot.size >= limit) return initialSnapshot.takeLast(limit)
			val remaining = (limit - initialSnapshot.size).coerceAtMost(MAX_UNSIGNED_SHORT)
			invokeUnit(
				loadedTimeline,
				PAGINATE_BACKWARDS_METHOD_NAME,
				arrayOf(Short::class.javaPrimitiveType ?: throw SnapshotAdapterFailure()),
				arrayOf(remaining.toShort()),
			)
			readTimelineSnapshot(loadedTimeline, listenerClass).takeLast(limit)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		} catch (e: Exception) {
			null
		} catch (e: LinkageError) {
			null
		} finally {
			closeQuietly(timeline)
			closeQuietly(room)
		}
	}

	private fun readTimelineSnapshot(
		timeline: Any,
		listenerClass: Class<*>,
	): List<MatrixMessageSnapshot> {
		val update = TimelineSnapshotUpdate()
		val listener = Proxy.newProxyInstance(
			listenerClass.classLoader,
			arrayOf(listenerClass),
		) { proxy, method, arguments ->
			when (method.name) {
				"onUpdate" -> update.onUpdate(arguments?.firstOrNull())
				"equals" -> proxy === arguments?.firstOrNull()
				"hashCode" -> System.identityHashCode(proxy)
				"toString" -> "MatrixTimelineListener"
				else -> null
			}
		}
		var listenerTask: Any? = null
		return try {
			listenerTask = invokeCloseableValue(
				timeline,
				"addListener",
				arrayOf(listenerClass),
				arrayOf(listener),
				::cancelAndCloseQuietly,
			)
			update.await(discoveryTimeoutMs)
		} finally {
			cancelAndCloseQuietly(listenerTask)
		}
	}

	private inner class TimelineSnapshotUpdate {
		private val stopped = AtomicBoolean(false)
		private val outcome = AtomicReference<Result<List<MatrixMessageSnapshot>>>()
		private val latch = CountDownLatch(1)

		fun onUpdate(argument: Any?): Any? {
			val diffs = try {
				(argument as? List<*>)?.map { it ?: throw SnapshotAdapterFailure() }
					?: throw SnapshotAdapterFailure()
			} catch (e: Exception) {
				publish(Result.failure(e), emptyList())
				return null
			}
			if (stopped.get()) {
				diffs.forEach(::destroyQuietly)
				return null
			}
			val result = try {
				Result.success(reduceTimelineDiffs(diffs).mapNotNull(::toMessageSnapshot))
			} catch (e: Exception) {
				Result.failure(e)
			} catch (e: LinkageError) {
				Result.failure(e)
			}
			publish(result, diffs)
			return null
		}

		private fun publish(result: Result<List<MatrixMessageSnapshot>>, diffs: List<Any>) {
			diffs.forEach(::destroyQuietly)
			if (stopped.compareAndSet(false, true)) {
				outcome.set(result)
				latch.countDown()
			}
		}

		fun await(timeoutMs: Long): List<MatrixMessageSnapshot> {
			if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
				stopped.set(true)
				throw SnapshotAdapterFailure()
			}
			return (outcome.get() ?: throw SnapshotAdapterFailure()).getOrThrow()
		}
	}

	private fun reduceTimelineDiffs(diffs: List<Any>): List<Any> {
		val items = ArrayList<Any>()
		for (diff in diffs) {
			when (diff.javaClass.simpleName) {
				"Append" -> items.addAll(readTimelineItems(diff, "getValues"))
				"Clear" -> items.clear()
				"Insert" -> items.add(
					readTimelineIndex(diff, "getIndex-pVg5ArA", items.size, true),
					readTimelineItem(diff),
				)
				"PopBack" -> items.removeAt(items.lastIndexOrFailure())
				"PopFront" -> items.removeAt(items.firstIndexOrFailure())
				"PushBack" -> items.add(readTimelineItem(diff))
				"PushFront" -> items.add(0, readTimelineItem(diff))
				"Remove" -> items.removeAt(readTimelineIndex(diff, "getIndex-pVg5ArA", items.size, false))
				"Reset" -> {
					items.clear()
					items.addAll(readTimelineItems(diff, "getValues"))
				}
				"Set" -> items[readTimelineIndex(diff, "getIndex-pVg5ArA", items.size, false)] =
					readTimelineItem(diff)
				"Truncate" -> {
					val length = readUnsignedInt(diff, "getLength-pVg5ArA")
					if (length < items.size) items.subList(length, items.size).clear()
				}
				else -> throw SnapshotAdapterFailure()
			}
		}
		return items
	}

	private fun readTimelineItems(diff: Any, methodName: String): List<Any> {
		val values = diff.javaClass.getMethod(methodName).invoke(diff) as? List<*>
			?: throw SnapshotAdapterFailure()
		return values.map { it ?: throw SnapshotAdapterFailure() }
	}

	private fun readTimelineItem(diff: Any): Any =
		diff.javaClass.getMethod("getValue").invoke(diff) ?: throw SnapshotAdapterFailure()

	private fun readTimelineIndex(diff: Any, methodName: String, size: Int, allowEnd: Boolean): Int {
		val index = readUnsignedInt(diff, methodName)
		val valid = if (allowEnd) index <= size else index < size
		if (!valid) throw SnapshotAdapterFailure()
		return index
	}

	private fun readUnsignedInt(target: Any, methodName: String): Int {
		val raw = target.javaClass.getMethod(methodName).invoke(target) as? Int
			?: throw SnapshotAdapterFailure()
		if (raw < 0) throw SnapshotAdapterFailure()
		return raw
	}

	private fun List<Any>.lastIndexOrFailure(): Int =
		lastIndex.takeIf { it >= 0 } ?: throw SnapshotAdapterFailure()

	private fun List<Any>.firstIndexOrFailure(): Int =
		if (isEmpty()) throw SnapshotAdapterFailure() else 0

	private fun toMessageSnapshot(timelineItem: Any): MatrixMessageSnapshot? {
		val event = timelineItem.javaClass.getMethod("asEvent").invoke(timelineItem) ?: return null
		return try {
			val eventClass = event.javaClass
			val identifier = eventClass.getMethod("getEventOrTransactionId").invoke(event)
			if (identifier.javaClass.simpleName != "EventId") return null
			val content = eventClass.getMethod("getContent").invoke(event)
			if (content.javaClass.simpleName != "MsgLike") return null
			val msgLike = content.javaClass.getMethod("getContent").invoke(content)
			val kind = msgLike.javaClass.getMethod("getKind").invoke(msgLike)
			if (kind.javaClass.simpleName != "Message") return null
			val message = kind.javaClass.getMethod("getContent").invoke(kind)
			val messageType = message.javaClass.getMethod("getMsgType").invoke(message)
			val timestampMillis = eventClass.getMethod(TIMESTAMP_GETTER_NAME).invoke(event) as Long
			MatrixMessageSnapshot(
				eventId = identifier.javaClass.getMethod("getEventId").invoke(identifier) as String,
				senderId = eventClass.getMethod("getSender").invoke(event) as String,
				bodyText = message.javaClass.getMethod("getBody").invoke(message) as String,
				originServerTimestampSeconds = (timestampMillis.toULong() / 1_000uL).toLong(),
				isOutgoing = eventClass.getMethod("isOwn").invoke(event) as Boolean,
				type = when (messageType.javaClass.simpleName) {
					"Image" -> ConnectorMessageType.PHOTO
					"Video", "Audio", "File", "Gallery" -> ConnectorMessageType.FILE
					else -> ConnectorMessageType.TEXT
				},
			)
		} finally {
			destroyQuietly(event)
		}
	}

	@Synchronized
	override fun logout() {
		val client = loginClient ?: return
		loginClient = null
		try {
			invokeUnit(client, "logout", emptyArray(), emptyArray())
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
		} catch (e: Exception) {
		} catch (e: LinkageError) {
		} finally {
			closeQuietly(client)
		}
	}

	@Synchronized
	override fun close() {
		closeQuietly(loginClient)
		loginClient = null
	}

	/**
	 * Bridges `ClientBuilder.build(Continuation<? super Client>): Object`'s suspend
	 * bytecode signature to a blocking call. `build()` may complete synchronously
	 * (no real suspension point hit; the invoked method returns the [Any] result
	 * directly) or asynchronously (the invoked method returns [COROUTINE_SUSPENDED]
	 * and later calls [Continuation.resumeWith] from another thread), so both paths
	 * are handled explicitly rather than assumed.
	 *
	 * A build that completes after [discoveryTimeoutMs] races the timing-out
	 * caller: [claimed] arbitrates which side owns the result so exactly one of
	 * them either uses it or closes it, never both and never neither.
	 */
	private fun buildClient(builder: Any, builderClass: Class<*>): Any {
		val buildMethod = builderClass.getMethod("build", Continuation::class.java)
		val outcome = AtomicReference<Result<Any?>>()
		val latch = CountDownLatch(1)
		val claimed = AtomicBoolean(false)
		val continuation = object : Continuation<Any?> {
			override val context: CoroutineContext = EmptyCoroutineContext
			override fun resumeWith(result: Result<Any?>) {
				outcome.set(result)
				latch.countDown()
				// If the caller already timed out and claimed abandonment, this
				// delivery has no owner left to close its client; do it here.
				if (!claimed.compareAndSet(false, true)) closeQuietly(result.getOrNull())
			}
		}
		val invokeResult = buildMethod.invoke(builder, continuation)
		if (invokeResult !== COROUTINE_SUSPENDED) return invokeResult ?: throw DiscoveryAdapterFailure()
		val completedInTime = try {
			latch.await(discoveryTimeoutMs, TimeUnit.MILLISECONDS)
		} catch (e: InterruptedException) {
			// Abandoning on interrupt is the same race as abandoning on timeout: if
			// resumeWith already delivered, we own its client and must close it
			// before rethrowing; otherwise any later delivery closes itself.
			if (!claimed.compareAndSet(false, true)) closeQuietly(outcome.get()?.getOrNull())
			throw e
		}
		if (!completedInTime && claimed.compareAndSet(false, true)) {
			// Won the race to claim abandonment before resumeWith could: no result
			// is available yet, and any later delivery closes itself.
			throw DiscoveryAdapterFailure()
		}
		// Either the latch reached zero before timing out, or resumeWith claimed
		// the result concurrently with our timeout check; either way it is safe
		// to consume the outcome now.
		return (outcome.get() ?: throw DiscoveryAdapterFailure()).getOrThrow()
			?: throw DiscoveryAdapterFailure()
	}

	private fun mapThrowable(throwable: Throwable): RecoverableErrorDetail =
		MatrixHomeserverDiscoveryFailureMapper.mapClientBuildExceptionClassName(
			throwable.javaClass.simpleName,
		)

	private fun mapLoginFailure(throwable: Throwable): RecoverableErrorDetail {
		if (throwable.javaClass.simpleName != "MatrixApi") return LOGIN_FAILED
		return try {
			val kind = throwable.javaClass.getMethod("getKind").invoke(throwable)
			if (kind.javaClass.simpleName in setOf("Forbidden", "Unauthorized")) {
				INVALID_CREDENTIALS
			} else {
				LOGIN_FAILED
			}
		} catch (e: ReflectiveOperationException) {
			LOGIN_FAILED
		}
	}

	private fun invokeUnit(
		target: Any,
		methodName: String,
		parameterTypes: Array<Class<*>>,
		arguments: Array<Any?>,
	) {
		val method = target.javaClass.getMethod(
			methodName,
			*(parameterTypes + Continuation::class.java),
		)
		val outcome = AtomicReference<Result<Any?>>()
		val latch = CountDownLatch(1)
		val continuation = object : Continuation<Any?> {
			override val context: CoroutineContext = EmptyCoroutineContext
			override fun resumeWith(result: Result<Any?>) {
				outcome.set(result)
				latch.countDown()
			}
		}
		val invokeResult = method.invoke(target, *(arguments + continuation))
		if (invokeResult !== COROUTINE_SUSPENDED) return
		if (!latch.await(discoveryTimeoutMs, TimeUnit.MILLISECONDS)) throw DiscoveryAdapterFailure()
		(outcome.get() ?: throw DiscoveryAdapterFailure()).getOrThrow()
	}

	/**
	 * Bridges a suspend SDK call that returns an owned closeable value. If delivery loses a race
	 * with timeout or interruption, [abandon] disposes the late value on the delivery thread.
	 */
	private fun invokeCloseableValue(
		target: Any,
		methodName: String,
		parameterTypes: Array<Class<*>>,
		arguments: Array<Any?>,
		abandon: (Any?) -> Unit,
	): Any {
		val method = target.javaClass.getMethod(
			methodName,
			*(parameterTypes + Continuation::class.java),
		)
		val outcome = AtomicReference<Result<Any?>>()
		val latch = CountDownLatch(1)
		val claimed = AtomicBoolean(false)
		val continuation = object : Continuation<Any?> {
			override val context: CoroutineContext = EmptyCoroutineContext
			override fun resumeWith(result: Result<Any?>) {
				outcome.set(result)
				latch.countDown()
				if (!claimed.compareAndSet(false, true)) abandon(result.getOrNull())
			}
		}
		val invokeResult = method.invoke(target, *(arguments + continuation))
		if (invokeResult !== COROUTINE_SUSPENDED) {
			return invokeResult ?: throw SnapshotAdapterFailure()
		}
		val completedInTime = try {
			latch.await(discoveryTimeoutMs, TimeUnit.MILLISECONDS)
		} catch (e: InterruptedException) {
			if (!claimed.compareAndSet(false, true)) abandon(outcome.get()?.getOrNull())
			throw e
		}
		if (!completedInTime && claimed.compareAndSet(false, true)) {
			throw SnapshotAdapterFailure()
		}
		return (outcome.get() ?: throw SnapshotAdapterFailure()).getOrThrow()
			?: throw SnapshotAdapterFailure()
	}

	private fun readHomeserverUrl(client: Any): String =
		(client.javaClass.getMethod("homeserver").invoke(client) as? String)
			?.takeIf { it.isNotEmpty() }
			?: throw DiscoveryAdapterFailure()

	private fun track(builders: MutableList<Any>, candidate: Any): Any {
		if (builders.none { it === candidate }) builders.add(candidate)
		return candidate
	}

	private fun closeQuietly(target: Any?) {
		if (target == null) return
		try {
			target.javaClass.getMethod("close").invoke(target)
		} catch (e: ReflectiveOperationException) {
		} catch (e: LinkageError) {
		}
	}

	private fun cancelAndCloseQuietly(target: Any?) {
		if (target == null) return
		try {
			target.javaClass.getMethod("cancel").invoke(target)
		} catch (e: ReflectiveOperationException) {
		} catch (e: LinkageError) {
		} finally {
			closeQuietly(target)
		}
	}

	private fun destroyQuietly(target: Any?) {
		if (target == null) return
		try {
			target.javaClass.getMethod("destroy").invoke(target)
		} catch (e: ReflectiveOperationException) {
		} catch (e: LinkageError) {
		}
	}

	/** Adapter-side failure (timeout, missing/blank result) with no SDK exception to map. */
	private class DiscoveryAdapterFailure : Exception()
	private class SnapshotAdapterFailure : Exception()

	private companion object {
		const val CLIENT_BUILDER_CLASS_NAME = "org.matrix.rustcomponents.sdk.ClientBuilder"
		const val SQLITE_STORE_BUILDER_CLASS_NAME = "org.matrix.rustcomponents.sdk.SqliteStoreBuilder"
		const val SESSION_CLASS_NAME = "org.matrix.rustcomponents.sdk.Session"
		const val SESSION_DELEGATE_CLASS_NAME = "org.matrix.rustcomponents.sdk.ClientSessionDelegate"
		const val SESSION_DELEGATE_FACTORY_CLASS_NAME =
			"org.briarproject.briar.matrix.DirectMatrixClientSessionDelegateFactory"
		const val TIMELINE_LISTENER_CLASS_NAME = "org.matrix.rustcomponents.sdk.TimelineListener"
		const val PAGINATE_BACKWARDS_METHOD_NAME = "paginateBackwards-vckuEUM"
		const val TIMESTAMP_GETTER_NAME = "getTimestamp-s-VKNKU"
		const val MAX_UNSIGNED_SHORT = 0xFFFF
		const val DEVICE_NAME = "Harbor"
		const val AEAD_TRANSFORMATION = "AES/GCM/NoPadding"
		const val GCM_NONCE_LENGTH_BYTES = 12
		const val GCM_TAG_LENGTH_BITS = 128
	}
}
