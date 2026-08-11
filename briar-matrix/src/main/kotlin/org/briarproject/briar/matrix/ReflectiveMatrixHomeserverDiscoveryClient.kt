package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Production [MatrixHomeserverDiscoveryClient] that invokes the pinned
 * `org.matrix.rustcomponents:sdk-android` `ClientBuilder.serverName(...).build()`
 * discovery path entirely over reflection, so `:briar-matrix` keeps zero
 * compile-time dependency on the SDK (see this module's `build.gradle` and
 * [MatrixHomeserverDiscoveryFailureMapper]'s docstring). Mirrors
 * `ReflectiveTelegramTdlibMessageClient`'s isolation approach for TDLib.
 * `build()` is a Kotlin suspend function; [buildClient] bridges its
 * `Continuation`-based bytecode signature to a blocking call so [discover]
 * itself stays synchronous per the interface contract. If the SDK classes
 * cannot be loaded (flag off / dependency absent) or discovery fails for any
 * reason, [discover] returns [DiscoveryResult.Failed] instead of throwing.
 */
class ReflectiveMatrixHomeserverDiscoveryClient(private val discoveryTimeoutMs: Long = 30_000L) :
	MatrixHomeserverDiscoveryClient {

	override fun discover(serverName: String): DiscoveryResult {
		var client: Any? = null
		return try {
			val builderClass = Class.forName(CLIENT_BUILDER_CLASS_NAME)
			var builder = builderClass.getConstructor().newInstance()
			builder = builderClass.getMethod("serverName", String::class.java).invoke(builder, serverName)
			builder = builderClass.getMethod("inMemoryStore").invoke(builder)
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
			closeClientQuietly(client)
		}
	}

	/**
	 * Bridges `ClientBuilder.build(Continuation<? super Client>): Object`'s suspend
	 * bytecode signature to a blocking call. `build()` may complete synchronously
	 * (no real suspension point hit; the invoked method returns the [Any] result
	 * directly) or asynchronously (the invoked method returns [COROUTINE_SUSPENDED]
	 * and later calls [Continuation.resumeWith] from another thread), so both paths
	 * are handled explicitly rather than assumed.
	 */
	private fun buildClient(builder: Any, builderClass: Class<*>): Any {
		val buildMethod = builderClass.getMethod("build", Continuation::class.java)
		val outcome = AtomicReference<Result<Any?>>()
		val latch = CountDownLatch(1)
		val continuation = object : Continuation<Any?> {
			override val context: CoroutineContext = EmptyCoroutineContext
			override fun resumeWith(result: Result<Any?>) {
				outcome.set(result)
				latch.countDown()
			}
		}
		val invokeResult = buildMethod.invoke(builder, continuation)
		if (invokeResult !== COROUTINE_SUSPENDED) return invokeResult ?: throw DiscoveryAdapterFailure()
		if (!latch.await(discoveryTimeoutMs, TimeUnit.MILLISECONDS)) throw DiscoveryAdapterFailure()
		return (outcome.get() ?: throw DiscoveryAdapterFailure()).getOrThrow()
			?: throw DiscoveryAdapterFailure()
	}

	private fun mapThrowable(throwable: Throwable): RecoverableErrorDetail =
		MatrixHomeserverDiscoveryFailureMapper.mapClientBuildExceptionClassName(
			throwable.javaClass.simpleName,
		)

	private fun readHomeserverUrl(client: Any): String =
		(client.javaClass.getMethod("homeserver").invoke(client) as? String)
			?.takeIf { it.isNotEmpty() }
			?: throw DiscoveryAdapterFailure()

	private fun closeClientQuietly(client: Any?) {
		if (client == null) return
		try {
			client.javaClass.getMethod("close").invoke(client)
		} catch (e: ReflectiveOperationException) {
		} catch (e: LinkageError) {
		}
	}

	/** Adapter-side failure (timeout, missing/blank result) with no SDK exception to map. */
	private class DiscoveryAdapterFailure : Exception()

	private companion object {
		const val CLIENT_BUILDER_CLASS_NAME = "org.matrix.rustcomponents.sdk.ClientBuilder"
	}
}
