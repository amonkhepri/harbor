package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_CREDENTIALS
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.LOGIN_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Production [MatrixHomeserverDiscoveryClient] and [MatrixLoginClient] that invoke the pinned
 * `org.matrix.rustcomponents:sdk-android` `ClientBuilder.serverName(...).build()`
 * discovery path entirely over reflection, so `:briar-matrix` keeps zero
 * compile-time dependency on the SDK (see this module's `build.gradle` and
 * [MatrixHomeserverDiscoveryFailureMapper]'s docstring). Mirrors
 * `ReflectiveTelegramTdlibMessageClient`'s isolation approach for TDLib.
 * `build()` is a Kotlin suspend function; [buildClient] bridges its
 * `Continuation`-based bytecode signature to a blocking call so [discover]
 * itself stays synchronous per the interface contract. Discovery uses only a
 * temporary client; login retains its client until logout or close.
 */
class ReflectiveMatrixHomeserverDiscoveryClient(private val discoveryTimeoutMs: Long = 30_000L) :
	MatrixHomeserverDiscoveryClient,
	MatrixLoginClient {

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
			client = buildClient(builder, builderClass)
			invokeUnit(
				client,
				"login",
				arrayOf(String::class.java, String::class.java, String::class.java, String::class.java),
				arrayOf(username, password, DEVICE_NAME, null),
			)
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
	 * Builds against [storeConfiguration]'s persistent store and asks the SDK
	 * whether that store already holds a session (`Client.session()` returns
	 * non-null). An empty/fresh store yields [RestoreResult.NotFound], not a
	 * failure.
	 */
	@Synchronized
	override fun restore(
		storeConfiguration: MatrixStoreConfiguration,
	): MatrixLoginClient.RestoreResult {
		val builders = ArrayList<Any>(3)
		var client: Any? = null
		return try {
			val builderClass = Class.forName(CLIENT_BUILDER_CLASS_NAME)
			var builder = track(builders, builderClass.getConstructor().newInstance())
			builder = track(builders, applyStore(builders, builder, builderClass, storeConfiguration))
			client = buildClient(builder, builderClass)
			val session = client.javaClass.getMethod("session").invoke(client)
			if (session == null) {
				MatrixLoginClient.RestoreResult.NotFound
			} else {
				val restored = client
				val homeserverUrl = readHomeserverUrl(restored)
				loginClient = restored
				client = null
				MatrixLoginClient.RestoreResult.Restored(homeserverUrl)
			}
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
		} finally {
			closeQuietly(client)
			for (i in builders.indices.reversed()) closeQuietly(builders[i])
		}
	}

	/** Applies [storeConfiguration] to [builder], tracking every wrapper it returns. */
	private fun applyStore(
		builders: MutableList<Any>,
		builder: Any,
		builderClass: Class<*>,
		storeConfiguration: MatrixStoreConfiguration?,
	): Any {
		if (storeConfiguration == null) {
			return builderClass.getMethod("inMemoryStore").invoke(builder)
		}
		val withPath = track(
			builders,
			builderClass.getMethod("sqlitePath", String::class.java)
				.invoke(builder, storeConfiguration.directory.absolutePath),
		)
		return builderClass.getMethod("passphrase", String::class.java)
			.invoke(withPath, encodeHex(storeConfiguration.copyEncryptionKey()))
	}

	private fun encodeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

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

	/** Adapter-side failure (timeout, missing/blank result) with no SDK exception to map. */
	private class DiscoveryAdapterFailure : Exception()

	private companion object {
		const val CLIENT_BUILDER_CLASS_NAME = "org.matrix.rustcomponents.sdk.ClientBuilder"
		const val DEVICE_NAME = "Harbor"
	}
}
