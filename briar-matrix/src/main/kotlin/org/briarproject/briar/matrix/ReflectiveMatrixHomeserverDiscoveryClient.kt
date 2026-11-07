package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_CREDENTIALS
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.LOGIN_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.briarproject.briar.api.connector.ConnectorAttachmentMetadata
import org.briarproject.briar.api.connector.ConnectorFileDownloader
import org.briarproject.briar.api.connector.ConnectorFileDownloader.DownloadResult
import org.briarproject.briar.api.connector.ConnectorFileDownloader.FailureCause
import org.briarproject.briar.api.connector.ConnectorFileSender
import org.briarproject.briar.api.connector.ConnectorImageSender
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReactionSummary
import org.briarproject.briar.api.connector.ConnectorUnreadMarker.PreparedMarkUnread
import org.briarproject.briar.api.connector.ConnectorRoomLeaver.PreparedLeave
import org.briarproject.briar.api.connector.ConnectorRoomObserver.RoomSubscription
import org.briarproject.briar.api.connector.ConnectorTypingObserver.TypingSubscription
import org.briarproject.briar.api.connector.ConnectorTypingSender.PreparedTyping
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
 * [MatrixMessageSnapshotClient], and [MatrixMessageSendClient] (text send MX-013, image send
 * MX-014, read marker MX-019) that invoke the pinned
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
	private val typingExecutor: Executor = Executors.newSingleThreadExecutor { task ->
		Thread(task, "matrix-typing").apply { isDaemon = true }
	},
) : MatrixHomeserverDiscoveryClient,
	MatrixLoginClient,
	MatrixRoomSnapshotClient,
	MatrixMessageSnapshotClient,
	MatrixMessageSendClient,
	MatrixFileDownloadClient {

	private var loginClient: Any? = null
	private val lifecycleLock = Any()
	private var activeGeneration: Any? = null
	private var activeStore: MatrixStoreConfiguration? = null
	private val activeDownloadDirs = java.util.Collections.newSetFromMap(
		java.util.concurrent.ConcurrentHashMap<File, Boolean>(),
	)
	private val activeAttachmentRoots = java.util.Collections.newSetFromMap(
		java.util.concurrent.ConcurrentHashMap<File, Boolean>(),
	)
	private class TypingLane {
		val queue = java.util.ArrayDeque<() -> Unit>()
		var busy = false
	}
	private val typingLanes = mutableMapOf<String, TypingLane>()
	private val activeRoomSubscriptions = mutableSetOf<RoomSubscription>()
	private val activeTypingSubscriptions = mutableSetOf<TypingSubscription>()
	private val inFlightReactions = java.util.Collections.synchronizedSet(mutableSetOf<String>())

	// Unlike invokeUnit, this lane waits for real native completion, not a timeout. A hung
	// operation cannot release a later stop/start out of order or block message sending.
	override fun prepareTyping(roomId: String): PreparedTyping? {
		val (generation, lane) = synchronized(lifecycleLock) {
			val generation = activeGeneration ?: return null
			generation to typingLanes.getOrPut(roomId) { TypingLane() }
		}
		return object : PreparedTyping {
			private val pendingStart = AtomicBoolean()
			override fun isCurrent() = synchronized(lifecycleLock) { activeGeneration === generation }
			override fun send(typing: Boolean, wanted: () -> Boolean) {
				if (typing && !pendingStart.compareAndSet(false, true)) return
				typingExecutor.execute {
					lane.queue.add operation@{
						if (typing) pendingStart.set(false)
						var room: Any? = null
						val completed = AtomicBoolean()
						fun complete() {
							if (!completed.compareAndSet(false, true)) return
							closeQuietly(room)
							typingExecutor.execute {
								lane.busy = false
								drainTyping(lane)
							}
						}
						try {
							// Authorization takes its own lock; never call it under SDK locks.
							if (typing && !wanted()) return@operation complete()
							synchronized(this@ReflectiveMatrixHomeserverDiscoveryClient) {
								synchronized(lifecycleLock) {
									if (isCurrent()) {
										val client = loginClient
										room = client?.javaClass?.getMethod("getRoom", String::class.java)
											?.invoke(client, roomId)
									}
								}
							}
							val target = room
							if (target == null || (typing && !wanted())) {
								complete()
							} else {
								synchronized(lifecycleLock) {
									if (!isCurrent()) {
										complete()
									} else {
										val continuation = object : Continuation<Unit> {
											override val context = EmptyCoroutineContext
											override fun resumeWith(result: Result<Unit>) = complete()
										}
										// SDK 26.08.05: fixed 4s expiry, 3s resend suppression.
										val result = target.javaClass.getMethod(
											"typingNotice",
											Boolean::class.javaPrimitiveType,
											Continuation::class.java,
										)
											.invoke(target, typing, continuation)
										if (result !== COROUTINE_SUSPENDED) complete()
									}
								}
							}
						} catch (e: Throwable) {
							complete()
						}
					}
					drainTyping(lane)
				}
			}
		}
	}

	private fun drainTyping(lane: TypingLane) {
		if (lane.busy || lane.queue.isEmpty()) return
		lane.busy = true
		lane.queue.removeFirst().invoke()
	}

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
			builder = if (serverName.startsWith("http://", ignoreCase = true) ||
				serverName.startsWith("https://", ignoreCase = true)
			) {
				track(
					builders,
					builderClass.getMethod("homeserverUrl", String::class.java).invoke(builder, serverName),
				)
			} else {
				track(
					builders,
					builderClass.getMethod("serverName", String::class.java).invoke(builder, serverName),
				)
			}
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
		val sessionGeneration = Any()
		beginGeneration(sessionGeneration)
		val builders = ArrayList<Any>(4)
		var client: Any? = null
		var success = false
		var claimedStore = false
		var failureDetail: RecoverableErrorDetail? = null
		return try {
			if (storeConfiguration != null) {
				claimStore(storeConfiguration)
				claimedStore = true
			}
			val builderClass = Class.forName(CLIENT_BUILDER_CLASS_NAME)
			var builder = track(builders, builderClass.getConstructor().newInstance())
			builder = track(
				builders,
				builderClass.getMethod("homeserverUrl", String::class.java).invoke(builder, homeserverUrl),
			)
			builder = track(builders, applyStore(builders, builder, builderClass, storeConfiguration))
			if (storeConfiguration != null) {
				builder =
					track(
						builders,
						installSessionDelegate(builder, builderClass, storeConfiguration, sessionGeneration),
					)
			}
			client = buildClient(builder, builderClass)
			invokeUnit(
				client,
				"login",
				arrayOf(String::class.java, String::class.java, String::class.java, String::class.java),
				arrayOf(username, password, DEVICE_NAME, null),
			)
			if (storeConfiguration != null) persistSession(client, storeConfiguration, sessionGeneration)
			activeStore = storeConfiguration
			loginClient = client
			client = null
			success = true
			NONE
		} catch (e: InvocationTargetException) {
			val mapped = mapLoginFailure(e.targetException ?: e)
			failureDetail = mapped
			mapped
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			LOGIN_FAILED
		} catch (e: ReflectiveOperationException) {
			LOGIN_FAILED
		} catch (e: LinkageError) {
			LOGIN_FAILED
		} catch (e: Exception) {
			val mapped = mapLoginFailure(e)
			failureDetail = mapped
			mapped
		} finally {
			if (!success) {
				synchronized(lifecycleLock) {
					if (activeGeneration === sessionGeneration) activeGeneration = null
					// Per Class D scope decision (MX-011): only a proven invalid/revoked
					// credential result deletes the sidecar. A transient/generic failure
					// (network, server error) must retain it rather than sign the user out.
					if (claimedStore && storeConfiguration != null && failureDetail == INVALID_CREDENTIALS) {
						sessionFile(storeConfiguration).delete()
					}
				}
			}
			closeQuietly(client)
			for (i in builders.indices.reversed()) closeQuietly(builders[i])
		}
	}

	/**
	 * Reads the `Session` persisted by a prior [login] into [storeConfiguration]'s
	 * sidecar file and feeds it back through `Client.restoreSession(Session,
	 * Continuation)` on a client freshly built against the same store. A missing
	 * sidecar file yields [MatrixLoginClient.RestoreResult.NotFound], not a
	 * failure; the store itself is not opened in that case. A store already opened in
	 * this process is quarantined until process exit, since close cannot prove quiescence;
	 * that surfaces as [MatrixLoginClient.RestoreResult.Failed] with `LOGIN_FAILED` (the
	 * same detail [login] reports for the identical collision), never [NotFound], so an
	 * intact on-disk session already claimed by this process is never mistaken for one
	 * that never existed.
	 */
	@Synchronized
	override fun restore(
		storeConfiguration: MatrixStoreConfiguration,
	): MatrixLoginClient.RestoreResult {
		val sessionGeneration = Any()
		beginGeneration(sessionGeneration)
		val file = sessionFile(storeConfiguration)
		if (!file.isFile) {
			synchronized(lifecycleLock) { activeGeneration = null }
			return MatrixLoginClient.RestoreResult.NotFound
		}
		val builders = ArrayList<Any>(4)
		var client: Any? = null
		var success = false
		var claimedStore = false
		var failureDetail: RecoverableErrorDetail? = null
		return try {
			val sessionClass = Class.forName(SESSION_CLASS_NAME)
			val session = readSessionFile(file, sessionClass, storeConfiguration)
			claimStore(storeConfiguration)
			claimedStore = true
			val builderClass = Class.forName(CLIENT_BUILDER_CLASS_NAME)
			var builder = track(builders, builderClass.getConstructor().newInstance())
			builder = track(
				builders,
				builderClass.getMethod("homeserverUrl", String::class.java)
					.invoke(builder, sessionClass.getMethod("getHomeserverUrl").invoke(session)),
			)
			builder = track(builders, applyStore(builders, builder, builderClass, storeConfiguration))
			builder =
				track(
					builders,
					installSessionDelegate(builder, builderClass, storeConfiguration, sessionGeneration),
				)
			client = buildClient(builder, builderClass)
			invokeUnit(client, "restoreSession", arrayOf(sessionClass), arrayOf(session))
			val homeserverUrl = readHomeserverUrl(client)
			activeStore = storeConfiguration
			loginClient = client
			client = null
			success = true
			MatrixLoginClient.RestoreResult.Restored(homeserverUrl)
		} catch (e: StoreAlreadyClaimedException) {
			failureDetail = LOGIN_FAILED
			MatrixLoginClient.RestoreResult.Failed(LOGIN_FAILED)
		} catch (e: DiscoveryAdapterFailure) {
			MatrixLoginClient.RestoreResult.NotFound
		} catch (e: InvocationTargetException) {
			val mapped = mapThrowable(e.targetException ?: e)
			failureDetail = mapped
			MatrixLoginClient.RestoreResult.Failed(mapped)
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
			if (!success) {
				synchronized(lifecycleLock) {
					if (activeGeneration === sessionGeneration) activeGeneration = null
					// Per Class D scope decision (MX-011): only a proven invalid/revoked
					// credential result deletes the sidecar. A transient/generic failure
					// (network, server error) must retain it rather than sign the user out.
					if (claimedStore && failureDetail == INVALID_CREDENTIALS) file.delete()
				}
			}
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

	/** Reads [client]'s post-login `Session` and persists it to [store]'s sidecar file. */
	private fun persistSession(client: Any, store: MatrixStoreConfiguration, generation: Any) {
		val session = client.javaClass.getMethod("session").invoke(client)
		saveSession(session, store, generation)
	}

	// SDK getters may block or re-enter lifecycle methods. Only local I/O and generation
	// validation run under this lock; never call native code while holding it.
	private fun saveSession(session: Any, store: MatrixStoreConfiguration, generation: Any) {
		synchronized(lifecycleLock) { requireGeneration(generation) }
		val bytes = serializeSession(session)
		try {
			synchronized(lifecycleLock) {
				requireGeneration(generation)
				writeEncryptedAtomic(sessionFile(store), bytes, store)
			}
		} finally {
			bytes.fill(0)
		}
	}

	private fun requireGeneration(generation: Any) {
		if (activeGeneration !== generation) throw IOException("Stale delegate callback")
	}

	private fun beginGeneration(generation: Any) {
		val (detached, detachedTyping) = synchronized(lifecycleLock) {
			activeGeneration = generation
			typingLanes.clear()
			Pair(detachRoomSubscriptions(), detachTypingSubscriptions())
		}
		cancelRoomSubscriptions(detached)
		cancelTypingSubscriptions(detachedTyping)
		val previous = loginClient
		loginClient = null
		activeStore = null
		closeQuietly(previous)
	}

	private fun claimStore(store: MatrixStoreConfiguration) = synchronized(openedStores) {
		// A wrapper's close does not establish native quiescence. Keep the directory
		// quarantined until process exit, including failed builds and failed restores.
		if (!openedStores.add(store.directory.canonicalPath)) {
			throw StoreAlreadyClaimedException()
		}
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
		generation: Any,
	): Any {
		val delegateClass = Class.forName(SESSION_DELEGATE_CLASS_NAME)
		val factory = sessionDelegateFactory ?: Class.forName(SESSION_DELEGATE_FACTORY_CLASS_NAME)
			.getConstructor()
			.newInstance() as MatrixClientSessionDelegateFactory
		val delegate = factory.create(
			saveSession = { session -> saveSession(session, storeConfiguration, generation) },
			retrieveSession = {
				val bytes = synchronized(lifecycleLock) {
					requireGeneration(generation)
					readDecrypted(sessionFile(storeConfiguration), storeConfiguration)
				}
				try {
					val session = deserializeSession(bytes, Class.forName(SESSION_CLASS_NAME))
					synchronized(lifecycleLock) {
						requireGeneration(generation)
						session
					}
				} finally {
					bytes.fill(0)
				}
			},
		)
		return builderClass.getMethod("setSessionDelegate", delegateClass).invoke(builder, delegate)
	}

	/** Copies SDK fields outside the lifecycle lock; [saveSession] fences the encrypted commit. */
	private fun serializeSession(session: Any): ByteArray {
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
		return plaintext
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

	/** Reconstructs an SDK `Session` reflectively from [file], matching [serializeSession]'s layout. */
	private fun readSessionFile(
		file: File,
		sessionClass: Class<*>,
		storeConfiguration: MatrixStoreConfiguration,
	): Any {
		val plaintext = readDecrypted(file, storeConfiguration)
		return try {
			deserializeSession(plaintext, sessionClass)
		} finally {
			plaintext.fill(0)
		}
	}

	private fun deserializeSession(plaintext: ByteArray, sessionClass: Class<*>): Any =
		DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
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

	@Synchronized
	override fun getRoomTopic(roomId: String): String? {
		val client = loginClient ?: return null
		var room: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
			room?.let(::readTopic)
		} catch (e: ReflectiveOperationException) {
			null
		} catch (e: LinkageError) {
			null
		} finally {
			closeQuietly(room)
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
			val (unreadCount, markedUnread) = readUnreadState(room)
			MatrixRoomSnapshot(id, displayName, unreadCount, markedUnread, readTopic(room))
		}
	} catch (e: InvocationTargetException) {
		null
	} catch (e: ReflectiveOperationException) {
		null
	} catch (e: LinkageError) {
		null
	}

	private fun readTopic(room: Any): String? = try {
		room.javaClass.getMethod("topic").invoke(room) as? String
	} catch (e: ReflectiveOperationException) {
		null
	} catch (e: LinkageError) {
		null
	}

	private fun readUnreadState(room: Any): Pair<Int?, Boolean?> {
		var roomInfo: Any? = null
		return try {
			roomInfo = invokeCloseableValue(
				target = room,
				methodName = "roomInfo",
				parameterTypes = emptyArray(),
				arguments = emptyArray(),
				abandon = ::destroyQuietly,
			)
			val method = roomInfo.javaClass.getMethod("getNumUnreadMessages-s-VKNKU")
			val unread = method.invoke(roomInfo) as? Number
			// getNumUnreadMessages-s-VKNKU returns an unsigned ULong whose underlying
			// primitive is boxed as a signed Long by reflection: a raw value with the
			// sign bit set is not a confirmed zero, so it must map to null, not be
			// clamped into the valid range.
			val count = unread?.toLong()?.takeIf { it >= 0 }
				?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
			count to (roomInfo.javaClass.getMethod("isMarkedUnread").invoke(roomInfo) as? Boolean)
		} catch (e: Exception) {
			null to null
		} catch (e: LinkageError) {
			null to null
		} finally {
			destroyQuietly(roomInfo)
		}
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
		var initialSnapshot: List<MatrixMessageSnapshot>? = null
		var room: Any? = null
		var timeline: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			val loadedTimeline =
				invokeCloseableValue(room, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
			timeline = loadedTimeline
			val listenerClass = Class.forName(TIMELINE_LISTENER_CLASS_NAME)
			val initialEvents = deduplicateSnapshots(
				readTimelineSnapshot(loadedTimeline, listenerClass),
			)
			initialSnapshot = initialEvents
			if (initialEvents.size >= limit) return initialEvents.takeLast(limit)
			val remaining = (limit - initialEvents.size).coerceAtMost(MAX_UNSIGNED_SHORT)
			invokeUnit(
				loadedTimeline,
				PAGINATE_BACKWARDS_METHOD_NAME,
				arrayOf(Short::class.javaPrimitiveType ?: throw SnapshotAdapterFailure()),
				arrayOf(remaining.toShort()),
			)
			deduplicateSnapshots(
				readTimelineSnapshot(loadedTimeline, listenerClass),
			).takeLast(limit)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			initialSnapshot
		} catch (e: Exception) {
			initialSnapshot
		} catch (e: LinkageError) {
			initialSnapshot
		} finally {
			closeQuietly(timeline)
			closeQuietly(room)
		}
	}

	/**
	 * Sends [text] as a plain-text message into [roomId] (MX-013). `Timeline
	 * .createMessageContent` is a synchronous call that builds a
	 * `RoomMessageEventContentWithoutRelation` from `MessageType.Text(TextMessageContent(body,
	 * formatted))`; the suspend `Timeline.send(content)` then fires it, returning an owned
	 * `SendHandle` bridged the same way [getRecentMessages] bridges `Room.timeline()`. Returns
	 * false, never throwing, when no session is retained, [roomId] cannot be found, or any step
	 * fails.
	 */
	@Synchronized
	override fun sendTextMessage(roomId: String, text: String): Boolean = sendText(roomId, text, null)

	/**
	 * Sends [text] as a reply to [inReplyToEventId] (MX-057) through the pinned AAR's suspend
	 * `Timeline.sendReply(RoomMessageEventContentWithoutRelation, String)`, which returns `Unit`
	 * and lets the SDK add the `m.relates_to.m.in_reply_to` relation (and forward any thread
	 * relation of the replied-to event). The message content is built exactly like
	 * [sendTextMessage]; a blank [inReplyToEventId] fails closed rather than sending ordinary text.
	 */
	@Synchronized
	override fun sendReplyMessage(roomId: String, inReplyToEventId: String, text: String): Boolean =
		inReplyToEventId.isNotBlank() && sendText(roomId, text, inReplyToEventId)

	private fun sendText(roomId: String, text: String, inReplyToEventId: String?): Boolean {
		val client = loginClient ?: return false
		var room: Any? = null
		var timeline: Any? = null
		var content: Any? = null
		var sendHandle: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			val loadedTimeline =
				invokeCloseableValue(room, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
			timeline = loadedTimeline
			val createdContent = createTextContent(loadedTimeline, text)
			content = createdContent
			val contentClass = Class.forName(ROOM_MESSAGE_EVENT_CONTENT_CLASS_NAME)
			if (inReplyToEventId == null) {
				sendHandle = invokeCloseableValue(
					loadedTimeline,
					"send",
					arrayOf(contentClass),
					arrayOf(createdContent),
					::closeQuietly,
				)
			} else {
				invokeUnit(
					loadedTimeline,
					"sendReply",
					arrayOf(contentClass, String::class.java),
					arrayOf(createdContent, inReplyToEventId),
				)
			}
			true
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		} catch (e: Exception) {
			false
		} catch (e: LinkageError) {
			false
		} finally {
			closeQuietly(sendHandle)
			closeQuietly(content)
			closeQuietly(timeline)
			closeQuietly(room)
		}
	}

	private fun createTextContent(timeline: Any, text: String): Any {
		val formattedBodyClass = Class.forName(FORMATTED_BODY_CLASS_NAME)
		val textContent = Class.forName(TEXT_MESSAGE_CONTENT_CLASS_NAME)
			.getConstructor(String::class.java, formattedBodyClass)
			.newInstance(text, null)
		val messageType = Class.forName(MESSAGE_TYPE_TEXT_CLASS_NAME)
			.getConstructor(textContent.javaClass)
			.newInstance(textContent)
		return timeline.javaClass
			.getMethod("createMessageContent", Class.forName(MESSAGE_TYPE_CLASS_NAME))
			.invoke(timeline, messageType)
			?: throw SnapshotAdapterFailure()
	}

	/**
	 * MX-058: suspend `Timeline.edit(EventOrTransactionId, EditedContent.RoomMessage)` (26.08.05),
	 * only after `getEventTimelineItemByEventId` shows an own, editable `MessageType.Text` event;
	 * foreign, media, redacted or unknown events and SDK failures return false, never throwing.
	 */
	@Synchronized
	override fun editTextMessage(roomId: String, eventId: String, text: String): Boolean {
		val client = loginClient ?: return false
		var room: Any? = null
		var timeline: Any? = null
		var item: Any? = null
		var content: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			val loadedTimeline =
				invokeCloseableValue(room, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
			timeline = loadedTimeline
			val loadedItem = invokeCloseableValue(
				loadedTimeline,
				"getEventTimelineItemByEventId",
				arrayOf(String::class.java),
				arrayOf(eventId),
				::destroyQuietly,
			)
			item = loadedItem
			if (!isOwnEditableText(loadedItem)) return false
			val createdContent = createTextContent(loadedTimeline, text)
			content = createdContent
			val editedContent = Class.forName(EDITED_CONTENT_ROOM_MESSAGE_CLASS_NAME)
				.getConstructor(Class.forName(ROOM_MESSAGE_EVENT_CONTENT_CLASS_NAME))
				.newInstance(createdContent)
			val eventIdObj = Class.forName(EVENT_ID_CLASS_NAME)
				.getConstructor(String::class.java)
				.newInstance(eventId)
			invokeUnit(
				loadedTimeline,
				"edit",
				arrayOf(
					Class.forName(EVENT_OR_TRANSACTION_ID_CLASS_NAME),
					Class.forName(EDITED_CONTENT_CLASS_NAME),
				),
				arrayOf(eventIdObj, editedContent),
			)
			true
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		} catch (e: Exception) {
			false
		} catch (e: LinkageError) {
			false
		} finally {
			closeQuietly(content)
			destroyQuietly(item)
			closeQuietly(timeline)
			closeQuietly(room)
		}
	}

	private fun isOwnEditableText(event: Any): Boolean {
		val eventClass = event.javaClass
		if (eventClass.getMethod("isOwn").invoke(event) != true) return false
		if (eventClass.getMethod("isEditable").invoke(event) != true) return false
		val content = eventClass.getMethod("getContent").invoke(event)
		if (content?.javaClass?.simpleName != "MsgLike") return false
		val msgLike = content.javaClass.getMethod("getContent").invoke(content)
		val kind = msgLike.javaClass.getMethod("getKind").invoke(msgLike)
		if (kind.javaClass.simpleName != "Message") return false
		val message = kind.javaClass.getMethod("getContent").invoke(kind)
		val messageType = message.javaClass.getMethod("getMsgType").invoke(message)
		return Class.forName(MESSAGE_TYPE_TEXT_CLASS_NAME).isInstance(messageType)
	}

	@Synchronized
	override fun prepareFileMessage(roomId: String): ConnectorFileSender.PreparedFileSend? {
		val capturedClient = loginClient ?: return null
		return ConnectorFileSender.PreparedFileSend { bytes, name, mime ->
			synchronized(this) {
				loginClient === capturedClient &&
					sendAttachment(roomId, bytes, name, mime, false)
			}
		}
	}

	/**
	 * Marks [roomId] read via the pinned AAR's suspend `Room.markAsRead(ReceiptType)` (MX-019),
	 * bridged the same way [invokeUnit] bridges `login`/`logout`. Sends `ReceiptType.READ_PRIVATE`
	 * (a private read receipt) to prevent metadata leak. Returns false, never throwing, when
	 * no session is retained, [roomId] cannot be found, or the SDK call fails.
	 */
	@Synchronized
	override fun markRead(roomId: String): Boolean = updateReadState(roomId, false)

	override fun prepareMarkUnread(roomId: String): PreparedMarkUnread? {
		val generation = synchronized(lifecycleLock) { activeGeneration } ?: return null
		return object : PreparedMarkUnread {
			override fun isCurrent(): Boolean = synchronized(lifecycleLock) {
				activeGeneration === generation
			}
			override fun markUnread(): Boolean =
				synchronized(this@ReflectiveMatrixHomeserverDiscoveryClient) {
					isCurrent() && updateReadState(roomId, true) && isCurrent()
				}
		}
	}

	override fun prepareLeave(roomId: String): PreparedLeave? {
		val generation = synchronized(lifecycleLock) { activeGeneration } ?: return null
		return object : PreparedLeave {
			override fun isCurrent() = synchronized(lifecycleLock) { activeGeneration === generation }
			override fun leave(): Boolean = synchronized(this@ReflectiveMatrixHomeserverDiscoveryClient) {
				if (!isCurrent()) return@synchronized false
				var room: Any? = null
				try {
					val client = loginClient ?: return@synchronized false
					room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
						?: return@synchronized false
					invokeUnit(room, "leave", emptyArray(), emptyArray())
					isCurrent()
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
					false
				} catch (e: Exception) {
					false
				} catch (e: LinkageError) {
					false
				} finally {
					closeQuietly(room)
				}
			}
		}
	}

	private fun updateReadState(roomId: String, unread: Boolean): Boolean {
		val client = loginClient ?: return false
		var room: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			if (!unread) {
				val receiptTypeClass = Class.forName(RECEIPT_TYPE_CLASS_NAME)
				val readReceiptType = receiptTypeClass.getField("READ_PRIVATE").get(null)
				invokeUnit(room, "markAsRead", arrayOf(receiptTypeClass), arrayOf(readReceiptType))
			}
			// SDK 26.08.05 explicitly leaves the flag unchanged in markAsRead.
			invokeUnit(room, "setUnreadFlag", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(unread))
			true
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		} catch (e: Exception) {
			false
		} catch (e: LinkageError) {
			false
		} finally {
			closeQuietly(room)
		}
	}

	@Synchronized
	override fun deleteMessage(roomId: String, eventId: String): Boolean {
		val client = loginClient ?: return false
		var room: Any? = null
		var timeline: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			val loadedTimeline =
				invokeCloseableValue(room, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
			timeline = loadedTimeline

			val eventIdClass = Class.forName(EVENT_ID_CLASS_NAME)
			val eventIdObj = eventIdClass.getConstructor(String::class.java).newInstance(eventId)

			invokeUnit(
				loadedTimeline,
				"redactEvent",
				arrayOf(
					Class.forName(EVENT_OR_TRANSACTION_ID_CLASS_NAME),
					String::class.java,
				),
				arrayOf(eventIdObj, null),
			)
			true
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		} catch (e: Exception) {
			false
		} catch (e: LinkageError) {
			false
		} finally {
			closeQuietly(timeline)
			closeQuietly(room)
		}
	}

	/**
	 * MX-059: suspend `Timeline.toggleReaction(EventOrTransactionId, String)` (26.08.05);
	 * duplicate in-flight submissions for the same (room, event, reaction) are rejected so
	 * rapid taps do not double-toggle or race. Duplicate registration is recorded before
	 * acquiring the client monitor so overlapping calls reject immediately rather than
	 * serializing. On timeout or cancellation, duplicate protection is retained until the
	 * native operation completes or cancels. Unknown events and SDK failures return false,
	 * never throwing.
	 */
	override fun sendReaction(roomId: String, eventId: String, reaction: String): Boolean {
		if (reaction.isBlank() || eventId.isBlank()) return false
		val flightKey = "$roomId:$eventId:$reaction"
		if (!inFlightReactions.add(flightKey)) return false
		return toggleReactionInternal(roomId, eventId, reaction, flightKey) != null
	}

	/**
	 * MX-060: removes the user's own [reaction] on [eventId] in [roomId].
	 * Tracks SDK ownership to verify that the logged-in user actually added [reaction] to this
	 * event before invoking the SDK's suspend `Timeline.toggleReaction(EventOrTransactionId, String)`.
	 * Reuses the opened timeline to avoid closing and reopening before toggle. If the state changes
	 * between lookup and toggle so that toggle adds the reaction (returning true) instead of
	 * removing it (returning false), recovers by toggling again to remove the newly added reaction
	 * and returns false without claiming success. Recovery is owned by the completion path and
	 * retains operation ownership (inFlightReactions guard) across the initial toggle and recovery
	 * even if the waiting caller times out or is interrupted, ensuring late additions are cleaned up
	 * and the guard is never leaked.
	 * If the event cannot be found, the user does not own
	 * the reaction, or the SDK call fails, returns false -- never throwing.
	 */
	override fun removeReaction(roomId: String, eventId: String, reaction: String): Boolean {
		if (reaction.isBlank() || eventId.isBlank() || roomId.isBlank()) return false
		val flightKey = "$roomId:$eventId:$reaction"
		if (!inFlightReactions.add(flightKey)) return false

		val client = loginClient
		if (client == null) {
			inFlightReactions.remove(flightKey)
			return false
		}
		val currentUserId = try {
			client.javaClass.getMethod("userId").invoke(client) as? String
		} catch (e: Exception) {
			null
		}
		if (currentUserId.isNullOrBlank()) {
			inFlightReactions.remove(flightKey)
			return false
		}

		var room: Any? = null
		var timeline: Any? = null
		val ownsReaction = try {
			val loadedRoom =
				client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
					?: throw SnapshotAdapterFailure()
			room = loadedRoom
			val loadedTimeline =
				invokeCloseableValue(loadedRoom, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
			timeline = loadedTimeline
			val loadedItem = invokeCloseableValue(
				loadedTimeline,
				"getEventTimelineItemByEventId",
				arrayOf(String::class.java),
				arrayOf(eventId),
				::destroyQuietly,
			)
			try {
				hasOwnReaction(loadedItem, reaction, currentUserId)
			} finally {
				destroyQuietly(loadedItem)
			}
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		} catch (e: Exception) {
			false
		} catch (e: LinkageError) {
			false
		}

		if (!ownsReaction) {
			closeQuietly(timeline)
			closeQuietly(room)
			inFlightReactions.remove(flightKey)
			return false
		}

		// Reuse the open room and timeline directly to avoid closing and reopening.
		val added = toggleReactionInternal(
			roomId,
			eventId,
			reaction,
			flightKey,
			room,
			timeline,
			recoverOnAdded = true,
		)
		return added == false
	}

	private fun hasOwnReaction(item: Any, reaction: String, currentUserId: String): Boolean {
		val eventClass = item.javaClass
		val content = eventClass.getMethod("getContent").invoke(item) ?: return false
		if (content.javaClass.simpleName != "MsgLike") return false
		val msgLike = content.javaClass.getMethod("getContent").invoke(content) ?: return false
		val reactions =
			msgLike.javaClass.getMethod("getReactions").invoke(msgLike) as? List<*> ?: return false
		return reactions.any { r ->
			if (r == null) return@any false
			val key = r.javaClass.getMethod("getKey").invoke(r) as? String
			if (key != reaction) return@any false
			val senders = r.javaClass.getMethod("getSenders").invoke(r) as? List<*> ?: return@any false
			senders.any { s ->
				try {
					s?.javaClass?.getMethod("getSenderId")?.invoke(s) == currentUserId
				} catch (e: Exception) {
					false
				}
			}
		}
	}

	private fun toggleReactionInternal(
		roomId: String,
		eventId: String,
		reaction: String,
		flightKey: String,
		existingRoom: Any? = null,
		existingTimeline: Any? = null,
		recoverOnAdded: Boolean = false,
	): Boolean? {
		val completed = AtomicBoolean(false)
		val outcome = AtomicReference<Result<Any?>>()
		val latch = CountDownLatch(1)
		val roomRef = AtomicReference<Any?>(existingRoom)
		val timelineRef = AtomicReference<Any?>(existingTimeline)

		val job: Any? = try {
			val jobKt = Class.forName("kotlinx.coroutines.JobKt")
			val jobMethod = jobKt.getMethod("Job", Class.forName("kotlinx.coroutines.Job"))
			jobMethod.invoke(null, null)
		} catch (e: Exception) {
			null
		}
		val coroutineContext: CoroutineContext = if (job is CoroutineContext) {
			job
		} else {
			EmptyCoroutineContext
		}
		fun cancelJob() {
			if (job != null) {
				try {
					val cancelMethod = job.javaClass.methods.firstOrNull {
						it.name == "cancel" &&
							it.parameterTypes.size == 1 &&
							CancellationException::class.java.isAssignableFrom(it.parameterTypes[0])
					}
					cancelMethod?.invoke(job, CancellationException("Reaction cancelled"))
				} catch (ignored: Exception) {}
			}
		}

		fun performRecovery() {
			val recoveryDone = AtomicBoolean(false)
			fun finishRecovery() {
				if (!recoveryDone.compareAndSet(false, true)) return
				try {
					inFlightReactions.remove(flightKey)
					closeQuietly(timelineRef.get())
					closeQuietly(roomRef.get())
				} finally {
					outcome.set(Result.success(true))
					latch.countDown()
				}
			}

			try {
				val loadedTimeline = timelineRef.get()
				if (loadedTimeline == null) {
					finishRecovery()
					return
				}
				val eventIdClass = Class.forName(EVENT_ID_CLASS_NAME)
				val eventIdObj = eventIdClass.getConstructor(String::class.java).newInstance(eventId)
				val toggleReactionMethod = loadedTimeline.javaClass.getMethod(
					"toggleReaction",
					Class.forName(EVENT_OR_TRANSACTION_ID_CLASS_NAME),
					String::class.java,
					Continuation::class.java,
				)
				val recoveryContinuation = object : Continuation<Any?> {
					override val context: CoroutineContext = EmptyCoroutineContext
					override fun resumeWith(result: Result<Any?>) {
						finishRecovery()
					}
				}
				val recoveryInvokeResult = toggleReactionMethod.invoke(
					loadedTimeline,
					eventIdObj,
					reaction,
					recoveryContinuation,
				)
				if (recoveryInvokeResult !== COROUTINE_SUSPENDED) {
					finishRecovery()
				}
			} catch (t: Throwable) {
				finishRecovery()
			}
		}

		fun complete(result: Result<Any?>?) {
			if (!completed.compareAndSet(false, true)) return
			if (recoverOnAdded && result?.getOrNull() == true) {
				performRecovery()
			} else {
				try {
					inFlightReactions.remove(flightKey)
					closeQuietly(timelineRef.get())
					closeQuietly(roomRef.get())
				} finally {
					if (result != null) outcome.set(result)
					latch.countDown()
				}
			}
		}

		val continuation = object : Continuation<Any?> {
			override val context: CoroutineContext = coroutineContext
			override fun resumeWith(result: Result<Any?>) {
				complete(result)
			}
		}

		var suspended = false
		return try {
			synchronized(this) {
				val client = loginClient ?: return null
				val loadedRoom = existingRoom ?: (
					client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
						?: throw SnapshotAdapterFailure()
				)
				roomRef.set(loadedRoom)
				val loadedTimeline = existingTimeline ?: (
					invokeCloseableValue(loadedRoom, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
				)
				timelineRef.set(loadedTimeline)

				val eventIdClass = Class.forName(EVENT_ID_CLASS_NAME)
				val eventIdObj = eventIdClass.getConstructor(String::class.java).newInstance(eventId)

				val toggleReactionMethod = loadedTimeline.javaClass.getMethod(
					"toggleReaction",
					Class.forName(EVENT_OR_TRANSACTION_ID_CLASS_NAME),
					String::class.java,
					Continuation::class.java,
				)

				val invokeResult = toggleReactionMethod.invoke(
					loadedTimeline,
					eventIdObj,
					reaction,
					continuation,
				)

				if (invokeResult === COROUTINE_SUSPENDED) {
					suspended = true
				} else {
					val added = invokeResult as? Boolean ?: true
					complete(Result.success(added))
					if (!recoverOnAdded || !added) {
						return added
					}
					suspended = true
				}
			}

			val completedInTime = try {
				latch.await(discoveryTimeoutMs, TimeUnit.MILLISECONDS)
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				cancelJob()
				return null
			}

			if (!completedInTime) {
				cancelJob()
				return null
			}

			val result = outcome.get() ?: return null
			if (result.isFailure) return null
			result.getOrNull() as? Boolean
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			cancelJob()
			null
		} catch (e: Exception) {
			null
		} catch (e: LinkageError) {
			null
		} finally {
			if (!suspended) {
				complete(null)
			}
		}
	}

	/** Uploads one image and awaits SDK completion; cancels an abandoned join before closing it. */
	@Synchronized
	override fun sendImageMessage(
		roomId: String,
		imageBytes: ByteArray,
		fileName: String,
		mimeType: String,
	): Boolean = sendAttachment(roomId, imageBytes, fileName, mimeType, true)

	// Both SDK calls return an owned join handle. Await join, cancel on failure, then release
	// the handle, metadata, timeline and room. UploadSource.Data creates no temporary file.
	private fun sendAttachment(
		roomId: String,
		bytes: ByteArray,
		fileName: String,
		mimeType: String,
		image: Boolean,
	): Boolean {
		val cap = if (image) ConnectorImageSender.MAX_IMAGE_BYTES else ConnectorFileSender.MAX_FILE_BYTES
		if (bytes.isEmpty() ||
			bytes.size > cap ||
			(image && !mimeType.startsWith("image/")) ||
			(!image && !mimeType.matches(Regex("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+"))) ||
			fileName.isBlank() ||
			roomId.isBlank()
		) {
			return false
		}
		val client = loginClient ?: return false
		var room: Any? = null
		var timeline: Any? = null
		var attachmentInfo: Any? = null
		var joinHandle: Any? = null
		var completed = false
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			val membership = room.javaClass.getMethod("membership").invoke(room) as? Enum<*>
			val encryption = room.javaClass.getMethod("encryptionState").invoke(room) as? Enum<*>
			if (membership?.name != "JOINED" || encryption?.name != "NOT_ENCRYPTED") return false
			val loadedTimeline =
				invokeCloseableValue(room, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
			timeline = loadedTimeline
			val uploadSourceClass = Class.forName(UPLOAD_SOURCE_CLASS_NAME)
			val uploadSource = Class.forName(UPLOAD_SOURCE_DATA_CLASS_NAME)
				.getConstructor(ByteArray::class.java, String::class.java)
				.newInstance(bytes, fileName)
			val uploadParameters = Class.forName(UPLOAD_PARAMETERS_CLASS_NAME)
				.getConstructor(
					uploadSourceClass,
					String::class.java,
					Class.forName(FORMATTED_BODY_CLASS_NAME),
					Class.forName(MENTIONS_CLASS_NAME),
					String::class.java,
					String::class.java,
				)
				.newInstance(uploadSource, null, null, null, null, null)
			val uLongClass = Class.forName(ULONG_CLASS_NAME)
			val createdInfo = if (image) {
				Class.forName(IMAGE_INFO_CLASS_NAME)
					.getConstructor(
						uLongClass,
						uLongClass,
						String::class.java,
						uLongClass,
						Class.forName(THUMBNAIL_INFO_CLASS_NAME),
						Class.forName(MEDIA_SOURCE_CLASS_NAME),
						String::class.java,
						java.lang.Boolean::class.java,
						Class.forName(DEFAULT_CONSTRUCTOR_MARKER_CLASS_NAME),
					)
					.newInstance(null, null, mimeType, bytes.size.toULong(), null, null, null, null, null)
			} else {
				Class.forName("org.matrix.rustcomponents.sdk.FileInfo")
					.getConstructor(
						String::class.java,
						uLongClass,
						Class.forName(THUMBNAIL_INFO_CLASS_NAME),
						Class.forName(MEDIA_SOURCE_CLASS_NAME),
						Class.forName(DEFAULT_CONSTRUCTOR_MARKER_CLASS_NAME),
					)
					.newInstance(mimeType, bytes.size.toULong(), null, null, null)
			}
			attachmentInfo = createdInfo
			val createdJoinHandle = if (image) {
				loadedTimeline.javaClass
					.getMethod(
						"sendImage",
						Class.forName(UPLOAD_PARAMETERS_CLASS_NAME),
						uploadSourceClass,
						Class.forName(IMAGE_INFO_CLASS_NAME),
					)
					.invoke(loadedTimeline, uploadParameters, null, createdInfo)
			} else {
				loadedTimeline.javaClass.getMethod(
					"sendFile",
					Class.forName(UPLOAD_PARAMETERS_CLASS_NAME),
					createdInfo.javaClass,
				).invoke(loadedTimeline, uploadParameters, createdInfo)
			}
			if (createdJoinHandle == null) throw SnapshotAdapterFailure()
			joinHandle = createdJoinHandle
			invokeUnit(createdJoinHandle, "join", emptyArray(), emptyArray())
			completed = true
			true
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		} catch (e: Exception) {
			false
		} catch (e: LinkageError) {
			false
		} finally {
			if (!completed && joinHandle != null) {
				try {
					joinHandle.javaClass.getMethod("cancel").invoke(joinHandle)
				} catch (ignored: Exception) {
				}
			}
			closeQuietly(joinHandle)
			destroyQuietly(attachmentInfo)
			closeQuietly(timeline)
			closeQuietly(room)
		}
	}

	private fun createTimelineListener(listenerClass: Class<*>, onUpdate: (Any?) -> Unit): Any =
		Proxy.newProxyInstance(
			listenerClass.classLoader,
			arrayOf(listenerClass),
		) { proxy, method, arguments ->
			when (method.name) {
				"onUpdate" -> {
					onUpdate(arguments?.firstOrNull())
					null
				}
				"equals" -> proxy === arguments?.firstOrNull()
				"hashCode" -> System.identityHashCode(proxy)
				"toString" -> "MatrixTimelineListener"
				else -> null
			}
		}

	private fun readTimelineSnapshot(
		timeline: Any,
		listenerClass: Class<*>,
	): List<MatrixMessageSnapshot> {
		val update = TimelineSnapshotUpdate()
		val listener = createTimelineListener(listenerClass) { argument ->
			update.onUpdate(argument)
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

	private fun deduplicateSnapshots(
		snapshots: List<MatrixMessageSnapshot>,
	): List<MatrixMessageSnapshot> {
		if (snapshots.isEmpty()) return emptyList()
		val deduplicated = LinkedHashMap<String, MatrixMessageSnapshot>()
		for (snapshot in snapshots) {
			val existing = deduplicated[snapshot.eventId]
			deduplicated[snapshot.eventId] = if (existing == null) {
				snapshot
			} else {
				mergeSnapshots(existing, snapshot)
			}
		}
		return deduplicated.values.toList()
	}

	private fun mergeSnapshots(
		first: MatrixMessageSnapshot,
		second: MatrixMessageSnapshot,
	): MatrixMessageSnapshot {
		if (first.type == ConnectorMessageType.DELETED ||
			second.type == ConnectorMessageType.DELETED
		) {
			val deletedBase = if (second.type == ConnectorMessageType.DELETED) second else first
			return deletedBase.copy(
				bodyText = "",
				type = ConnectorMessageType.DELETED,
				isEdited = false,
				attachment = null,
				reactions = emptyList(),
			)
		}
		val base = when {
			first.isEdited && !second.isEdited -> first
			!first.isEdited && second.isEdited -> second
			else -> second
		}
		val attachment = if (base == first) {
			first.attachment ?: second.attachment
		} else {
			second.attachment ?: first.attachment
		}
		val isReply = second.isReply || first.isReply
		return base.copy(
			reactions = base.reactions,
			attachment = attachment,
			isReply = isReply,
		)
	}

	override fun getMediaThumbnail(
		attachment: ConnectorAttachmentMetadata,
		width: Int,
		height: Int,
	): ByteArray? {
		if (width <= 0 || height <= 0) return null
		val (client, generation) = synchronized(this) {
			val gen = synchronized(lifecycleLock) { activeGeneration }
			Pair(loginClient, gen)
		}
		if (client == null || generation == null) return null

		val sourceJson = attachment.thumbnailSourceJson // encrypted originals: no full-res fetch
			?: attachment.mediaSourceJson?.also { if ("\"key\"" in it) return null }
		val sourceUrl = attachment.thumbnailUri ?: attachment.uri
		if (sourceJson.isNullOrBlank() && sourceUrl.isNullOrBlank()) return null

		var mediaSource: Any? = null
		return try {
			val mediaSourceClass = Class.forName(MEDIA_SOURCE_CLASS_NAME)
			mediaSource = createMediaSource(mediaSourceClass, sourceJson, sourceUrl) ?: return null
			val method = client.javaClass.methods.firstOrNull {
				it.name.startsWith("getMediaThumbnail") && it.parameterTypes.size == 4
			} ?: return null
			val outcome = AtomicReference<Result<Any?>>()
			val latch = CountDownLatch(1)
			val continuation = object : Continuation<Any?> {
				override val context: CoroutineContext = EmptyCoroutineContext
				override fun resumeWith(result: Result<Any?>) {
					outcome.set(result)
					latch.countDown()
				}
			}
			val invokeResult = method.invoke(
				client,
				mediaSource,
				width.toLong(),
				height.toLong(),
				continuation,
			)
			val resultBytes = if (invokeResult !== COROUTINE_SUSPENDED) {
				invokeResult as? ByteArray
			} else {
				if (!latch.await(discoveryTimeoutMs, TimeUnit.MILLISECONDS)) return null
				outcome.get()?.getOrNull() as? ByteArray
			}
			val isCurrent = synchronized(lifecycleLock) { activeGeneration === generation }
			if (!isCurrent) null else resultBytes
		} catch (e: Exception) {
			null
		} finally {
			destroyQuietly(mediaSource)
		}
	}

	override fun getMediaThumbnail(mediaUrl: String, width: Int, height: Int): ByteArray? =
		getMediaThumbnail(ConnectorAttachmentMetadata(uri = mediaUrl), width, height)

	@Synchronized
	override fun observeRoom(roomId: String, onUpdate: () -> Unit): RoomSubscription? {
		if (roomId.isBlank()) return null
		val generation = synchronized(lifecycleLock) { activeGeneration } ?: return null
		val client = loginClient ?: return null
		var room: Any? = null
		var timeline: Any? = null
		var listenerTask: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			val membership = room.javaClass.getMethod("membership").invoke(room) as? Enum<*>
			if (membership?.name != "JOINED") {
				closeQuietly(room)
				return null
			}
			val loadedTimeline =
				invokeCloseableValue(room, "timeline", emptyArray(), emptyArray(), ::closeQuietly)
			timeline = loadedTimeline
			val initialDelivered = AtomicBoolean(false)
			val subscriptionCancelled = AtomicBoolean(false)
			val listenerClass = Class.forName(TIMELINE_LISTENER_CLASS_NAME)
			val listener = createTimelineListener(listenerClass) { argument ->
				val diffs = try {
					(argument as? List<*>)?.mapNotNull { it } ?: emptyList()
				} catch (e: Exception) {
					emptyList<Any>()
				}
				try {
					if (subscriptionCancelled.get()) return@createTimelineListener
					val current = synchronized(lifecycleLock) { activeGeneration === generation }
					if (!current) return@createTimelineListener
					if (initialDelivered.compareAndSet(false, true)) {
						return@createTimelineListener
					}
					if (diffs.isNotEmpty()) {
						onUpdate()
					}
				} finally {
					diffs.forEach(::destroyQuietly)
				}
			}
			val createdTask = invokeCloseableValue(
				loadedTimeline,
				"addListener",
				arrayOf(listenerClass),
				arrayOf(listener),
				::cancelAndCloseQuietly,
			)
			listenerTask = createdTask
			val retainedRoom = room
			val retainedTimeline = timeline
			val sub = object : RoomSubscription {
				override fun cancel() {
					if (!subscriptionCancelled.compareAndSet(false, true)) return
					synchronized(lifecycleLock) { activeRoomSubscriptions.remove(this) }
					cancelAndCloseQuietly(createdTask)
					closeQuietly(retainedTimeline)
					closeQuietly(retainedRoom)
				}

				override fun close() = cancel()
			}
			val current = synchronized(lifecycleLock) {
				(activeGeneration === generation).also { if (it) activeRoomSubscriptions.add(sub) }
			}
			if (!current) {
				// Native teardown must run outside lifecycleLock (see saveSession).
				sub.cancel()
				return null
			}
			sub
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			cancelAndCloseQuietly(listenerTask)
			closeQuietly(timeline)
			closeQuietly(room)
			null
		} catch (e: Exception) {
			cancelAndCloseQuietly(listenerTask)
			closeQuietly(timeline)
			closeQuietly(room)
			null
		} catch (e: LinkageError) {
			cancelAndCloseQuietly(listenerTask)
			closeQuietly(timeline)
			closeQuietly(room)
			null
		}
	}

	// Caller holds lifecycleLock. Only detaches; the native teardown happens in
	// cancelRoomSubscriptions after the lock is released, because a native cancel/close may
	// wait for an in-flight listener callback that itself takes lifecycleLock.
	private fun detachRoomSubscriptions(): List<RoomSubscription> {
		val detached = activeRoomSubscriptions.toList()
		activeRoomSubscriptions.clear()
		return detached
	}

	private fun cancelRoomSubscriptions(subscriptions: List<RoomSubscription>) {
		subscriptions.forEach { it.cancel() }
	}

	private fun detachTypingSubscriptions(): List<TypingSubscription> {
		val detached = activeTypingSubscriptions.toList()
		activeTypingSubscriptions.clear()
		return detached
	}

	private fun cancelTypingSubscriptions(subscriptions: List<TypingSubscription>) {
		subscriptions.forEach { it.cancel() }
	}

	@Synchronized
	override fun observeTyping(
		roomId: String,
		onTypingChanged: (isTyping: Boolean) -> Unit,
	): TypingSubscription? {
		if (roomId.isBlank()) return null
		val generation = synchronized(lifecycleLock) { activeGeneration } ?: return null
		val client = loginClient ?: return null
		var room: Any? = null
		var listenerTask: Any? = null
		return try {
			room = client.javaClass.getMethod("getRoom", String::class.java).invoke(client, roomId)
				?: throw SnapshotAdapterFailure()
			val membership = room.javaClass.getMethod("membership").invoke(room) as? Enum<*>
			if (membership?.name != "JOINED") {
				closeQuietly(room)
				return null
			}
			val currentUserId = try {
				client.javaClass.getMethod("userId").invoke(client) as? String
			} catch (e: Exception) {
				null
			}
			val subscriptionCancelled = AtomicBoolean(false)
			val listenerClass = Class.forName(TYPING_NOTIFICATIONS_LISTENER_CLASS_NAME)
			val listener = createTypingNotificationsListener(listenerClass) { argument ->
				val userIds = try {
					(argument as? List<*>)?.filterIsInstance<String>() ?: emptyList()
				} catch (e: Exception) {
					emptyList<String>()
				}
				if (subscriptionCancelled.get()) return@createTypingNotificationsListener
				val current = synchronized(lifecycleLock) { activeGeneration === generation }
				if (!current) return@createTypingNotificationsListener
				val peerTyping = userIds.any { it.isNotBlank() && it != currentUserId }
				onTypingChanged(peerTyping)
			}
			val createdTask = room.javaClass.getMethod(
				"subscribeToTypingNotifications",
				listenerClass,
			).invoke(room, listener)
			listenerTask = createdTask
			val retainedRoom = room
			val sub = object : TypingSubscription {
				override fun cancel() {
					if (!subscriptionCancelled.compareAndSet(false, true)) return
					synchronized(lifecycleLock) { activeTypingSubscriptions.remove(this) }
					cancelAndCloseQuietly(createdTask)
					closeQuietly(retainedRoom)
				}

				override fun close() = cancel()
			}
			val current = synchronized(lifecycleLock) {
				(activeGeneration === generation).also { if (it) activeTypingSubscriptions.add(sub) }
			}
			if (!current) {
				sub.cancel()
				return null
			}
			sub
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			cancelAndCloseQuietly(listenerTask)
			closeQuietly(room)
			null
		} catch (e: Exception) {
			cancelAndCloseQuietly(listenerTask)
			closeQuietly(room)
			null
		} catch (e: LinkageError) {
			cancelAndCloseQuietly(listenerTask)
			closeQuietly(room)
			null
		}
	}

	private fun createTypingNotificationsListener(
		listenerClass: Class<*>,
		onCall: (Any?) -> Unit,
	): Any = Proxy.newProxyInstance(
		listenerClass.classLoader,
		arrayOf(listenerClass),
	) { proxy, method, arguments ->
		when (method.name) {
			"call" -> {
				onCall(arguments?.firstOrNull())
				null
			}
			"equals" -> proxy === arguments?.firstOrNull()
			"hashCode" -> System.identityHashCode(proxy)
			"toString" -> "MatrixTypingNotificationsListener"
			else -> null
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

	private fun readReactionSummaries(
		msgLike: Any,
		currentUserId: String? = null,
	): List<ConnectorReactionSummary> {
		val reactions = msgLike.javaClass.getMethod("getReactions").invoke(msgLike) as? List<*>
			?: throw SnapshotAdapterFailure()
		return reactions.mapNotNull { reaction ->
			reaction ?: throw SnapshotAdapterFailure()
			val key = reaction.javaClass.getMethod("getKey").invoke(reaction) as? String
				?: throw SnapshotAdapterFailure()
			val senders = reaction.javaClass.getMethod("getSenders").invoke(reaction) as? List<*>
				?: throw SnapshotAdapterFailure()
			if (key.isEmpty() || senders.isEmpty()) {
				null
			} else {
				val isOwn = if (!currentUserId.isNullOrBlank()) {
					senders.any { sender ->
						try {
							sender?.javaClass?.getMethod("getSenderId")?.invoke(sender) == currentUserId
						} catch (ignored: Exception) {
							false
						}
					}
				} else {
					false
				}
				ConnectorReactionSummary(key, senders.size, isOwn)
			}
		}
	}

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
			// sdk-android:26.08.05 exposes Redacted as a payload-free singleton.
			if (kind.javaClass.simpleName == "Redacted") {
				val timestamp = eventClass.getMethod(TIMESTAMP_GETTER_NAME).invoke(event) as Long
				return MatrixMessageSnapshot(
					eventId = identifier.javaClass.getMethod("getEventId").invoke(identifier) as String,
					senderId = eventClass.getMethod("getSender").invoke(event) as String,
					bodyText = "",
					originServerTimestampSeconds = (timestamp.toULong() / 1_000uL).toLong(),
					isOutgoing = eventClass.getMethod("isOwn").invoke(event) as Boolean,
					type = ConnectorMessageType.DELETED,
				)
			}
			if (kind.javaClass.simpleName != "Message") return null
			val message = kind.javaClass.getMethod("getContent").invoke(kind)
			val messageType = message.javaClass.getMethod("getMsgType").invoke(message)
			val timestampMillis = eventClass.getMethod(TIMESTAMP_GETTER_NAME).invoke(event) as Long
			// getInReplyTo() is only used to check presence: msgLike's owning event.destroy()
			// below recursively destroys this entire nested content chain (documented in
			// mx007_m3_item2_timeline_scoping.md), so no separate destroy call is needed here,
			// consistent with message/kind/msgLike/content also being left undestroyed.
			val inReplyTo = msgLike.javaClass.getMethod("getInReplyTo").invoke(msgLike)
			val currentUserId = try {
				loginClient?.let { it.javaClass.getMethod("userId").invoke(it) as? String }
			} catch (ignored: Exception) {
				null
			}
			val reactions = readReactionSummaries(msgLike, currentUserId)
			val rawTypeName = messageType.javaClass.simpleName
			val typeName = if (rawTypeName == "Companion") {
				messageType.javaClass.enclosingClass?.simpleName ?: rawTypeName
			} else {
				rawTypeName
			}
			val attachment = readAttachmentMetadata(messageType)
			MatrixMessageSnapshot(
				eventId = identifier.javaClass.getMethod("getEventId").invoke(identifier) as String,
				senderId = eventClass.getMethod("getSender").invoke(event) as String,
				bodyText = message.javaClass.getMethod("getBody").invoke(message) as String,
				originServerTimestampSeconds = (timestampMillis.toULong() / 1_000uL).toLong(),
				isOutgoing = eventClass.getMethod("isOwn").invoke(event) as Boolean,
				type = when (typeName) {
					"Image" -> ConnectorMessageType.PHOTO
					"Video", "Audio", "File", "Gallery" -> ConnectorMessageType.FILE
					else -> ConnectorMessageType.TEXT
				},
				isEdited = message.javaClass.getMethod("isEdited").invoke(message) as Boolean,
				isReply = inReplyTo != null,
				reactions = reactions,
				attachment = attachment,
			)
		} finally {
			destroyQuietly(event)
		}
	}

	private fun readAttachmentMetadata(messageType: Any?): ConnectorAttachmentMetadata? {
		if (messageType == null) return null
		val rawTypeName = messageType.javaClass.simpleName
		val typeName = if (rawTypeName == "Companion") {
			messageType.javaClass.enclosingClass?.simpleName ?: rawTypeName
		} else {
			rawTypeName
		}
		if (typeName !in ATTACHMENT_TYPE_NAMES) return null
		return try {
			val contentMethod = messageType.javaClass.methods.firstOrNull {
				it.name == "getContent" && it.parameterTypes.isEmpty()
			} ?: return null
			val content = contentMethod.invoke(messageType) ?: return null
			val contentClass = content.javaClass
			val filenameMethod = contentClass.methods.firstOrNull {
				it.name == "getFilename" && it.parameterTypes.isEmpty()
			}
			val rawFilename = filenameMethod?.invoke(content) as? String
			val infoMethod = contentClass.methods.firstOrNull {
				it.name == "getInfo" && it.parameterTypes.isEmpty()
			}
			val info = infoMethod?.invoke(content)
			var rawMime: String? = null
			var rawSize: Long? = null
			if (info != null) {
				val infoClass = info.javaClass
				val mimeMethod = infoClass.methods.firstOrNull {
					it.name == "getMimetype" && it.parameterTypes.isEmpty()
				}
				rawMime = mimeMethod?.invoke(info) as? String
				val sizeMethod = infoClass.methods.firstOrNull {
					it.name.startsWith("getSize") && it.parameterTypes.isEmpty()
				}
				val sizeObj = sizeMethod?.invoke(info)
				rawSize = sizeObj?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
			}
			val sourceMethod = contentClass.methods.firstOrNull {
				it.name == "getSource" && it.parameterTypes.isEmpty()
			}
			val source = sourceMethod?.invoke(content)
			var mediaUrl: String? = null
			var mediaJson: String? = null
			if (source != null) {
				val urlMethod = source.javaClass.methods.firstOrNull {
					it.name == "url" && it.parameterTypes.isEmpty()
				}
				mediaUrl = urlMethod?.invoke(source) as? String
				val jsonMethod = source.javaClass.methods.firstOrNull {
					it.name == "toJson" && it.parameterTypes.isEmpty()
				}
				mediaJson = jsonMethod?.invoke(source) as? String
			}
			var thumbUrl: String? = null
			var thumbJson: String? = null
			if (info != null) {
				val thumbSourceMethod = info.javaClass.methods.firstOrNull {
					it.name == "getThumbnailSource" && it.parameterTypes.isEmpty()
				}
				val thumbSource = thumbSourceMethod?.invoke(info)
				if (thumbSource != null) {
					val urlMethod = thumbSource.javaClass.methods.firstOrNull {
						it.name == "url" && it.parameterTypes.isEmpty()
					}
					thumbUrl = urlMethod?.invoke(thumbSource) as? String
					val jsonMethod = thumbSource.javaClass.methods.firstOrNull {
						it.name == "toJson" && it.parameterTypes.isEmpty()
					}
					thumbJson = jsonMethod?.invoke(thumbSource) as? String
				}
			}
			ConnectorAttachmentMetadata(
				fileName = rawFilename,
				mimeType = rawMime,
				size = rawSize,
				uri = mediaUrl,
				mediaSourceJson = mediaJson,
				thumbnailUri = thumbUrl,
				thumbnailSourceJson = thumbJson,
			)
		} catch (e: Exception) {
			null
		}
	}

	override fun downloadFile(
		attachment: ConnectorAttachmentMetadata,
		destinationDir: File,
		isCancelled: () -> Boolean,
	): DownloadResult {
		val (client, generation) = synchronized(this) {
			val gen = synchronized(lifecycleLock) { activeGeneration }
			Pair(loginClient, gen)
		}
		if (client == null || generation == null) {
			return DownloadResult.Failure(FailureCause.LOGGED_OUT)
		}

		if (isCancelled()) return DownloadResult.Failure(FailureCause.CANCELLED)

		val declaredSize = attachment.size
		if (declaredSize == null ||
			declaredSize <= 0L ||
			declaredSize > ConnectorFileDownloader.MAX_FILE_BYTES
		) {
			return DownloadResult.Failure(FailureCause.OVERSIZE)
		}

		val mediaSourceJson = attachment.mediaSourceJson
		val mediaUrl = attachment.uri
		if (mediaSourceJson.isNullOrBlank() && mediaUrl.isNullOrBlank()) {
			return DownloadResult.Failure(FailureCause.NOT_FOUND)
		}

		if (!destinationDir.exists()) destinationDir.mkdirs()
		val attachmentsRoot: File = if (destinationDir.name == "matrix_attachments") {
			destinationDir
		} else if (destinationDir.parentFile?.name == "matrix_attachments") {
			destinationDir.parentFile!!
		} else {
			destinationDir
		}
		synchronized(activeDownloadDirs) {
			activeDownloadDirs.add(destinationDir)
		}
		synchronized(activeAttachmentRoots) {
			activeAttachmentRoots.add(attachmentsRoot)
		}
		val store = synchronized(this) { activeStore }
		if (store != null) {
			try {
				val rootsFile = File(store.directory, "matrix_attachment_roots")
				val rootPath = attachmentsRoot.absolutePath
				val existing = if (rootsFile.exists()) rootsFile.readLines() else emptyList()
				if (!existing.contains(rootPath)) {
					rootsFile.appendText(rootPath + "\n")
				}
			} catch (ignored: Exception) {}
		}

		var mediaSource: Any? = null
		var handle: Any? = null
		var downloadedFile: File? = null
		val stagingDir = File(destinationDir, ".staging_${UUID.randomUUID()}")
		stagingDir.mkdirs()

		return try {
			val mediaSourceClass = Class.forName(MEDIA_SOURCE_CLASS_NAME)
			mediaSource = createMediaSource(mediaSourceClass, mediaSourceJson, mediaUrl)
				?: return DownloadResult.Failure(FailureCause.NOT_FOUND)

			if (isCancelled()) return DownloadResult.Failure(FailureCause.CANCELLED)

			val safeFileName = sanitizeAttachmentFileName(attachment.fileName)
			val rawHandle = invokeMediaDownload(
				client = client,
				mediaSourceClass = mediaSourceClass,
				mediaSource = mediaSource,
				fileName = safeFileName,
				mimeType = attachment.mimeType ?: "application/octet-stream",
				stagingDir = stagingDir,
				generation = generation,
				isCancelled = isCancelled,
			)
			handle = rawHandle

			val isCurrent = synchronized(lifecycleLock) { activeGeneration === generation }
			if (!isCurrent) {
				cleanUpHandleAndFile(handle, null, stagingDir)
				return DownloadResult.Failure(FailureCause.LOGGED_OUT)
			}

			if (isCancelled()) {
				cleanUpHandleAndFile(handle, null, stagingDir)
				return DownloadResult.Failure(FailureCause.CANCELLED)
			}

			val pathMethod = rawHandle.javaClass.getMethod("path")
			val path = pathMethod.invoke(rawHandle) as? String
			if (path == null) {
				cleanUpHandleAndFile(handle, null, stagingDir)
				return DownloadResult.Failure(FailureCause.NETWORK_ERROR)
			}

			val tempFile = File(path)
			val canonicalDestPath = destinationDir.canonicalPath
			val destPrefix = if (canonicalDestPath.endsWith(File.separator)) {
				canonicalDestPath
			} else {
				canonicalDestPath + File.separator
			}

			val canonicalTempPath = tempFile.canonicalPath
			if (!canonicalTempPath.startsWith(destPrefix)) {
				cleanUpHandleAndFile(handle, null, stagingDir)
				return DownloadResult.Failure(FailureCause.NETWORK_ERROR)
			}

			if (isCancelled()) {
				cleanUpHandleAndFile(handle, null, stagingDir)
				return DownloadResult.Failure(FailureCause.CANCELLED)
			}

			if (!tempFile.exists()) {
				cleanUpHandleAndFile(handle, null, stagingDir)
				return DownloadResult.Failure(FailureCause.NETWORK_ERROR)
			}

			if (tempFile.length() > ConnectorFileDownloader.MAX_FILE_BYTES) {
				cleanUpHandleAndFile(handle, null, stagingDir)
				return DownloadResult.Failure(FailureCause.OVERSIZE)
			}

			val targetFile = File(destinationDir, "${UUID.randomUUID()}_$safeFileName")
			val canonicalTargetPath = targetFile.canonicalPath
			if (!canonicalTargetPath.startsWith(destPrefix)) {
				cleanUpHandleAndFile(handle, null, stagingDir)
				return DownloadResult.Failure(FailureCause.NETWORK_ERROR)
			}

			val persistMethod = rawHandle.javaClass.methods.firstOrNull {
				it.name == "persist" && it.parameterTypes.size == 1
			}
			val persisted = (persistMethod?.invoke(rawHandle, targetFile.absolutePath) as? Boolean) == true
			if (!persisted || !targetFile.exists()) {
				cleanUpHandleAndFile(handle, targetFile, stagingDir)
				return DownloadResult.Failure(FailureCause.NETWORK_ERROR)
			}
			downloadedFile = targetFile

			try {
				stagingDir.deleteRecursively()
			} catch (ignored: Exception) {}

			if (isCancelled()) {
				cleanUpHandleAndFile(handle, targetFile, stagingDir)
				return DownloadResult.Failure(FailureCause.CANCELLED)
			}

			if (targetFile.length() > ConnectorFileDownloader.MAX_FILE_BYTES) {
				cleanUpHandleAndFile(handle, targetFile, stagingDir)
				return DownloadResult.Failure(FailureCause.OVERSIZE)
			}

			DownloadResult.Success(targetFile)
		} catch (e: DownloadCancelledException) {
			cleanUpHandleAndFile(handle, downloadedFile, stagingDir)
			DownloadResult.Failure(FailureCause.CANCELLED)
		} catch (e: DownloadLoggedOutException) {
			cleanUpHandleAndFile(handle, downloadedFile, stagingDir)
			DownloadResult.Failure(FailureCause.LOGGED_OUT)
		} catch (e: DownloadOversizeException) {
			cleanUpHandleAndFile(handle, downloadedFile, stagingDir)
			DownloadResult.Failure(FailureCause.OVERSIZE)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			cleanUpHandleAndFile(handle, downloadedFile, stagingDir)
			DownloadResult.Failure(FailureCause.CANCELLED)
		} catch (e: Exception) {
			cleanUpHandleAndFile(handle, downloadedFile, stagingDir)
			val isCancellation = e is CancellationException ||
				e.javaClass.name == "kotlinx.coroutines.CancellationException" ||
				isCancelled()
			if (isCancellation) {
				DownloadResult.Failure(FailureCause.CANCELLED)
			} else {
				DownloadResult.Failure(FailureCause.NETWORK_ERROR)
			}
		} finally {
			try {
				if (stagingDir.exists()) stagingDir.deleteRecursively()
			} catch (ignored: Exception) {}
			closeQuietly(handle)
			destroyQuietly(mediaSource)
		}
	}

	private fun invokeMediaDownload(
		client: Any,
		mediaSourceClass: Class<*>,
		mediaSource: Any,
		fileName: String,
		mimeType: String,
		stagingDir: File,
		generation: Any,
		isCancelled: () -> Boolean,
		timeoutMs: Long = 300_000L,
	): Any {
		val method = client.javaClass.getMethod(
			"getMediaFile",
			mediaSourceClass,
			String::class.java,
			String::class.java,
			Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE,
			String::class.java,
			Continuation::class.java,
		)
		val job: Any? = try {
			val jobKt = Class.forName("kotlinx.coroutines.JobKt")
			val jobMethod = jobKt.getMethod("Job", Class.forName("kotlinx.coroutines.Job"))
			jobMethod.invoke(null, null)
		} catch (e: Exception) {
			null
		}
		val coroutineContext: CoroutineContext = if (job is CoroutineContext) {
			job
		} else {
			EmptyCoroutineContext
		}
		fun cancelJob() {
			if (job != null) {
				try {
					val cancelMethod = job.javaClass.methods.firstOrNull {
						it.name == "cancel" &&
							it.parameterTypes.size == 1 &&
							CancellationException::class.java.isAssignableFrom(it.parameterTypes[0])
					}
					cancelMethod?.invoke(job, CancellationException("Media download cancelled"))
				} catch (ignored: Exception) {}
			}
		}
		val outcome = AtomicReference<Result<Any?>>()
		val latch = CountDownLatch(1)
		val claimed = AtomicBoolean(false)
		val continuation = object : Continuation<Any?> {
			override val context: CoroutineContext = coroutineContext
			override fun resumeWith(result: Result<Any?>) {
				outcome.set(result)
				latch.countDown()
				if (!claimed.compareAndSet(false, true)) {
					cleanUpHandle(result.getOrNull())
				}
			}
		}
		val invokeResult = method.invoke(
			client,
			mediaSource,
			fileName,
			mimeType,
			false,
			stagingDir.absolutePath,
			continuation,
		)
		if (invokeResult !== COROUTINE_SUSPENDED) {
			if (isCancelled()) {
				cancelJob()
				cleanUpHandle(invokeResult)
				throw DownloadCancelledException()
			}
			val currentGen = synchronized(lifecycleLock) { activeGeneration }
			if (currentGen !== generation) {
				cancelJob()
				cleanUpHandle(invokeResult)
				throw DownloadLoggedOutException()
			}
			return invokeResult ?: throw SnapshotAdapterFailure()
		}
		val pollIntervalMs = 50L
		val deadline = System.currentTimeMillis() + timeoutMs
		while (true) {
			if (isCancelled()) {
				cancelJob()
				if (claimed.compareAndSet(false, true)) {
					cleanUpHandle(outcome.get()?.getOrNull())
					throw DownloadCancelledException()
				}
			}
			val currentGen = synchronized(lifecycleLock) { activeGeneration }
			if (currentGen !== generation) {
				cancelJob()
				if (claimed.compareAndSet(false, true)) {
					cleanUpHandle(outcome.get()?.getOrNull())
					throw DownloadLoggedOutException()
				}
			}
			val writtenBytes = runCatching {
				stagingDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
			}.getOrDefault(0L)
			if (writtenBytes > ConnectorFileDownloader.MAX_FILE_BYTES) {
				cancelJob()
				if (claimed.compareAndSet(false, true)) {
					cleanUpHandle(outcome.get()?.getOrNull())
					throw DownloadOversizeException()
				}
			}
			if (latch.await(pollIntervalMs, TimeUnit.MILLISECONDS)) {
				break
			}
			if (System.currentTimeMillis() >= deadline) {
				cancelJob()
				if (claimed.compareAndSet(false, true)) {
					cleanUpHandle(outcome.get()?.getOrNull())
					throw SnapshotAdapterFailure()
				}
			}
		}
		return (outcome.get() ?: throw SnapshotAdapterFailure()).getOrThrow()
			?: throw SnapshotAdapterFailure()
	}

	private fun cleanUpHandle(handle: Any?) {
		if (handle == null) return
		try {
			val path = handle.javaClass.getMethod("path").invoke(handle) as? String
			if (path != null) {
				val file = File(path)
				if (file.exists()) file.delete()
			}
		} catch (ignored: Exception) {}
		closeQuietly(handle)
		destroyQuietly(handle)
	}

	private fun cleanUpHandleAndFile(handle: Any?, file: File?, stagingDir: File? = null) {
		try {
			if (file != null && file.exists()) file.delete()
		} catch (ignored: Exception) {}
		try {
			if (stagingDir != null && stagingDir.exists()) stagingDir.deleteRecursively()
		} catch (ignored: Exception) {}
		cleanUpHandle(handle)
	}

	private fun createMediaSource(mediaSourceClass: Class<*>, json: String?, url: String?): Any? {
		val companion = try {
			mediaSourceClass.getField("Companion").get(null)
		} catch (e: Exception) {
			null
		}
		val methods = if (companion != null) companion.javaClass.methods else mediaSourceClass.methods
		val receiver = companion
		if (!json.isNullOrBlank()) {
			val fromJson = methods.firstOrNull {
				it.name == "fromJson" &&
					it.parameterTypes.size == 1 &&
					it.parameterTypes[0] == String::class.java
			}
			if (fromJson != null) {
				try {
					return fromJson.invoke(receiver, json)
				} catch (ignored: Exception) {}
			}
		}
		if (!url.isNullOrBlank()) {
			val fromUrl = methods.firstOrNull {
				it.name == "fromUrl" &&
					it.parameterTypes.size == 1 &&
					it.parameterTypes[0] == String::class.java
			}
			if (fromUrl != null) {
				try {
					return fromUrl.invoke(receiver, url)
				} catch (ignored: Exception) {}
			}
		}
		return null
	}

	@Synchronized
	override fun logout() {
		val (detached, detachedTyping) = synchronized(lifecycleLock) {
			activeGeneration = null
			typingLanes.clear()
			val r = detachRoomSubscriptions()
			val t = detachTypingSubscriptions()
			activeStore?.let { sessionFile(it).delete() }
			Pair(r, t)
		}
		cancelRoomSubscriptions(detached)
		cancelTypingSubscriptions(detachedTyping)
		val downloadDirsToClear = synchronized(activeDownloadDirs) {
			val copy = ArrayList(activeDownloadDirs)
			activeDownloadDirs.clear()
			copy
		}
		for (dir in downloadDirsToClear) {
			try {
				dir.deleteRecursively()
			} catch (ignored: Exception) {}
		}
		val rootsToClear = synchronized(activeAttachmentRoots) {
			val copy = ArrayList(activeAttachmentRoots)
			activeAttachmentRoots.clear()
			copy
		}
		val store = activeStore
		if (store != null) {
			try {
				val rootsFile = File(store.directory, "matrix_attachment_roots")
				if (rootsFile.exists()) {
					rootsFile.readLines().forEach { line ->
						val trimmed = line.trim()
						if (trimmed.isNotEmpty()) rootsToClear.add(File(trimmed))
					}
					rootsFile.delete()
				}
			} catch (ignored: Exception) {}
		}
		for (root in rootsToClear) {
			try {
				root.deleteRecursively()
			} catch (ignored: Exception) {}
		}
		activeStore = null
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
		val (detached, detachedTyping) = synchronized(lifecycleLock) {
			activeGeneration = null
			typingLanes.clear()
			Pair(detachRoomSubscriptions(), detachTypingSubscriptions())
		}
		cancelRoomSubscriptions(detached)
		cancelTypingSubscriptions(detachedTyping)
		val downloadDirsToClear = synchronized(activeDownloadDirs) {
			val copy = ArrayList(activeDownloadDirs)
			activeDownloadDirs.clear()
			copy
		}
		for (dir in downloadDirsToClear) {
			try {
				dir.deleteRecursively()
			} catch (ignored: Exception) {}
		}
		val rootsToClear = synchronized(activeAttachmentRoots) {
			val copy = ArrayList(activeAttachmentRoots)
			activeAttachmentRoots.clear()
			copy
		}
		val store = activeStore
		if (store != null) {
			try {
				val rootsFile = File(store.directory, "matrix_attachment_roots")
				if (rootsFile.exists()) {
					rootsFile.readLines().forEach { line ->
						val trimmed = line.trim()
						if (trimmed.isNotEmpty()) rootsToClear.add(File(trimmed))
					}
					rootsFile.delete()
				}
			} catch (ignored: Exception) {}
		}
		for (root in rootsToClear) {
			try {
				root.deleteRecursively()
			} catch (ignored: Exception) {}
		}
		val client = loginClient
		loginClient = null
		activeStore = null
		closeQuietly(client)
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
	private class DownloadCancelledException : Exception()
	private class DownloadLoggedOutException : Exception()
	private class DownloadOversizeException : Exception()

	/**
	 * [claimStore] found its directory already in [openedStores]. Kept distinct from a
	 * generic [IOException] so [restore] can report it as a recoverable [LOGIN_FAILED]
	 * failure instead of folding it into the same catch clause that maps a genuinely
	 * missing or unreadable sidecar to [MatrixLoginClient.RestoreResult.NotFound].
	 */
	private class StoreAlreadyClaimedException :
		IOException("Matrix store already opened in this process")

	private companion object {
		val ATTACHMENT_TYPE_NAMES = setOf("File", "Image", "Video", "Audio")
		val openedStores = HashSet<String>()
		const val CLIENT_BUILDER_CLASS_NAME = "org.matrix.rustcomponents.sdk.ClientBuilder"
		const val SQLITE_STORE_BUILDER_CLASS_NAME = "org.matrix.rustcomponents.sdk.SqliteStoreBuilder"
		const val SESSION_CLASS_NAME = "org.matrix.rustcomponents.sdk.Session"
		const val SESSION_DELEGATE_CLASS_NAME = "org.matrix.rustcomponents.sdk.ClientSessionDelegate"
		const val SESSION_DELEGATE_FACTORY_CLASS_NAME =
			"org.briarproject.briar.matrix.DirectMatrixClientSessionDelegateFactory"
		const val TIMELINE_LISTENER_CLASS_NAME = "org.matrix.rustcomponents.sdk.TimelineListener"
		const val TYPING_NOTIFICATIONS_LISTENER_CLASS_NAME =
			"org.matrix.rustcomponents.sdk.TypingNotificationsListener"
		const val FORMATTED_BODY_CLASS_NAME = "org.matrix.rustcomponents.sdk.FormattedBody"
		const val TEXT_MESSAGE_CONTENT_CLASS_NAME = "org.matrix.rustcomponents.sdk.TextMessageContent"
		const val MESSAGE_TYPE_CLASS_NAME = "org.matrix.rustcomponents.sdk.MessageType"
		const val MESSAGE_TYPE_TEXT_CLASS_NAME = "org.matrix.rustcomponents.sdk.MessageType\$Text"
		const val ROOM_MESSAGE_EVENT_CONTENT_CLASS_NAME =
			"org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation"
		const val UPLOAD_SOURCE_CLASS_NAME = "org.matrix.rustcomponents.sdk.UploadSource"
		const val UPLOAD_SOURCE_DATA_CLASS_NAME = "org.matrix.rustcomponents.sdk.UploadSource\$Data"
		const val UPLOAD_PARAMETERS_CLASS_NAME = "org.matrix.rustcomponents.sdk.UploadParameters"
		const val MENTIONS_CLASS_NAME = "org.matrix.rustcomponents.sdk.Mentions"
		const val IMAGE_INFO_CLASS_NAME = "org.matrix.rustcomponents.sdk.ImageInfo"
		const val THUMBNAIL_INFO_CLASS_NAME = "org.matrix.rustcomponents.sdk.ThumbnailInfo"
		const val EVENT_OR_TRANSACTION_ID_CLASS_NAME =
			"org.matrix.rustcomponents.sdk.EventOrTransactionId"
		const val EVENT_ID_CLASS_NAME = "org.matrix.rustcomponents.sdk.EventOrTransactionId\$EventId"
		const val EDITED_CONTENT_CLASS_NAME = "org.matrix.rustcomponents.sdk.EditedContent"
		const val EDITED_CONTENT_ROOM_MESSAGE_CLASS_NAME =
			"org.matrix.rustcomponents.sdk.EditedContent\$RoomMessage"
		const val MEDIA_SOURCE_CLASS_NAME = "org.matrix.rustcomponents.sdk.MediaSource"
		const val RECEIPT_TYPE_CLASS_NAME = "org.matrix.rustcomponents.sdk.ReceiptType"
		const val ULONG_CLASS_NAME = "kotlin.ULong"
		const val DEFAULT_CONSTRUCTOR_MARKER_CLASS_NAME = "kotlin.jvm.internal.DefaultConstructorMarker"
		const val PAGINATE_BACKWARDS_METHOD_NAME = "paginateBackwards-vckuEUM"
		const val TIMESTAMP_GETTER_NAME = "getTimestamp-s-VKNKU"
		const val MAX_UNSIGNED_SHORT = 0xFFFF
		const val DEVICE_NAME = "Harbor"
		const val AEAD_TRANSFORMATION = "AES/GCM/NoPadding"
		const val GCM_NONCE_LENGTH_BYTES = 12
		const val GCM_TAG_LENGTH_BITS = 128
	}
}

internal fun sanitizeAttachmentFileName(rawName: String?): String {
	if (rawName.isNullOrBlank()) {
		return "attachment_" + UUID.randomUUID().toString()
	}
	val baseName = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
	val stripped = baseName.trimStart('.')
	val safeChars = stripped.filter {
		it != '/' && it != '\\' && it != '\u0000' && it != '\r' && it != '\n'
	}.trim()
	if (safeChars.isEmpty() || safeChars == "." || safeChars == "..") {
		return "attachment_" + UUID.randomUUID().toString()
	}
	val capped = if (safeChars.length > 128) {
		val ext = safeChars.substringAfterLast('.', "")
		if (ext.isNotEmpty() && ext.length < 16) {
			safeChars.take(128 - ext.length - 1) + "." + ext
		} else {
			safeChars.take(128)
		}
	} else {
		safeChars
	}
	return capped.ifEmpty { "attachment_" + UUID.randomUUID().toString() }
}
