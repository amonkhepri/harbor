package org.matrix.rustcomponents.sdk

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Minimal same-FQN fakes for the pinned `org.matrix.rustcomponents:sdk-android`
 * types [ReflectiveMatrixHomeserverDiscoveryClient][org.briarproject.briar.matrix.ReflectiveMatrixHomeserverDiscoveryClient]
 * resolves via reflection. Mirrors `org.drinkless.tdlib.Client.kt`'s same-package
 * test double for TDLib: only compiled into `:briar-android`'s test source set, so
 * it does not collide with the real AAR, which is only pulled onto any classpath
 * when `harbor.matrixConnector.enabled=true` (default test runs leave it off).
 */
sealed class ClientBuildException(message: String) : Exception(message) {
	class InvalidServerName : ClientBuildException("invalid server name")
	class ServerUnreachable : ClientBuildException("server unreachable")
	class Generic : ClientBuildException("generic failure")
}

class Client internal constructor(
	private val homeserverUrl: String?,
	private val storePath: String? = null,
) {
	fun homeserver(): String? = homeserverUrl

	/** Non-null once [login] has persisted to [storePath]; mirrors the real SDK's restore check. */
	fun session(): Any? = storePath?.takeIf { FakeMatrixSdkState.isPersisted(it) }

	suspend fun login(
		username: String,
		password: String,
		initialDeviceName: String?,
		deviceId: String?,
	) {
		FakeMatrixSdkState.loginCallCount++
		storePath?.let { FakeMatrixSdkState.markPersisted(it) }
	}
	suspend fun logout() {
		FakeMatrixSdkState.logoutCallCount++
		storePath?.let { FakeMatrixSdkState.clearPersisted(it) }
	}
	fun close() {
		FakeMatrixSdkState.clientCloseCount++
	}
}

/**
 * Mirrors the pinned SDK's real shape: `ClientBuilder` is `AutoCloseable`, and
 * [serverName] and [inMemoryStore] each return a distinct closeable wrapper
 * rather than mutating the receiver, so a fix that only closes one instance
 * still leaks the others.
 */
class ClientBuilder : AutoCloseable {

	fun serverName(serverName: String): ClientBuilder {
		FakeMatrixSdkState.lastServerName = serverName
		return ClientBuilder()
	}

	fun inMemoryStore(): ClientBuilder {
		FakeMatrixSdkState.inMemoryStoreCalled = true
		FakeMatrixSdkState.lastSqlitePath = null
		return ClientBuilder()
	}

	fun homeserverUrl(homeserverUrl: String): ClientBuilder {
		FakeMatrixSdkState.lastHomeserverUrl = homeserverUrl
		return ClientBuilder()
	}

	fun sqlitePath(path: String): ClientBuilder {
		FakeMatrixSdkState.lastSqlitePath = path
		FakeMatrixSdkState.inMemoryStoreCalled = false
		return ClientBuilder()
	}

	fun passphrase(passphrase: String): ClientBuilder {
		FakeMatrixSdkState.lastPassphrase = passphrase
		return ClientBuilder()
	}

	override fun close() {
		FakeMatrixSdkState.builderCloseCount++
	}

	suspend fun build(): Client {
		FakeMatrixSdkState.buildCallCount++
		FakeMatrixSdkState.failureToThrow?.let { failure ->
			if (!FakeMatrixSdkState.failAsynchronously) throw failure
		}
		// A build only carries a persistent store path when inMemoryStore() was not
		// called, mirroring the reflective client's mutually exclusive builder calls.
		val storePath = FakeMatrixSdkState.lastSqlitePath.takeUnless {
			FakeMatrixSdkState.inMemoryStoreCalled
		}
		if (!FakeMatrixSdkState.suspendAsynchronously) {
			return Client(
				FakeMatrixSdkState.homeserverUrlToReturn,
				storePath,
			)
		}
		return suspendCoroutine { continuation ->
			Thread {
				if (FakeMatrixSdkState.asyncDelayMs > 0) Thread.sleep(FakeMatrixSdkState.asyncDelayMs)
				val asyncFailure = FakeMatrixSdkState.failureToThrow
				if (asyncFailure != null && FakeMatrixSdkState.failAsynchronously) {
					continuation.resumeWithException(asyncFailure)
				} else {
					continuation.resume(Client(FakeMatrixSdkState.homeserverUrlToReturn, storePath))
				}
			}.start()
		}
	}
}

object FakeMatrixSdkState {
	var lastServerName: String? = null
	var inMemoryStoreCalled = false
	var homeserverUrlToReturn: String? = "https://matrix.example.org"
	var failureToThrow: ClientBuildException? = null
	var failAsynchronously = false
	var suspendAsynchronously = false
	var asyncDelayMs = 0L
	var buildCallCount = 0
	var clientCloseCount = 0
	var builderCloseCount = 0
	var lastHomeserverUrl: String? = null
	var lastSqlitePath: String? = null
	var lastPassphrase: String? = null
	var loginCallCount = 0
	var logoutCallCount = 0

	private val persistedStorePaths = mutableSetOf<String>()

	fun isPersisted(storePath: String): Boolean = storePath in persistedStorePaths
	fun markPersisted(storePath: String) {
		persistedStorePaths.add(storePath)
	}
	fun clearPersisted(storePath: String) {
		persistedStorePaths.remove(storePath)
	}

	fun reset() {
		lastServerName = null
		inMemoryStoreCalled = false
		homeserverUrlToReturn = "https://matrix.example.org"
		failureToThrow = null
		failAsynchronously = false
		suspendAsynchronously = false
		asyncDelayMs = 0L
		buildCallCount = 0
		clientCloseCount = 0
		builderCloseCount = 0
		lastHomeserverUrl = null
		lastSqlitePath = null
		lastPassphrase = null
		loginCallCount = 0
		logoutCallCount = 0
		persistedStorePaths.clear()
	}
}
