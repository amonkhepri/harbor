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
 *
 * The shapes below match the pinned `sdk-android:26.08.05` AAR's decompiled public
 * surface: `ClientBuilder.sqliteStore(SqliteStoreBuilder)`, not a `sqlitePath`/
 * `passphrase` pair on `ClientBuilder` itself; `SqliteStoreBuilder(dataPath,
 * cachePath).passphrase(String)`; `Client.session()` throwing [ClientException]
 * until a session exists; and `Client.restoreSession(Session, Continuation)`.
 */
sealed class ClientBuildException(message: String) : Exception(message) {
	class InvalidServerName : ClientBuildException("invalid server name")
	class ServerUnreachable : ClientBuildException("server unreachable")
	class Generic : ClientBuildException("generic failure")
}

class ClientException(message: String) : Exception(message)

enum class SlidingSyncVersion { NONE, NATIVE }

class Session(
	val accessToken: String,
	val refreshToken: String?,
	val userId: String,
	val deviceId: String,
	val homeserverUrl: String,
	val oauthData: String?,
	val slidingSyncVersion: SlidingSyncVersion,
)

/** Mirrors the pinned SDK's `ClientSessionDelegate`: the app-supplied keychain bridge the SDK calls back into whenever it saves or looks up a `Session`, in particular after an internal token refresh. */
interface ClientSessionDelegate {
	fun retrieveSessionFromKeychain(userId: String): Session
	fun saveSessionInKeychain(session: Session)
}

class Client internal constructor(
	private val homeserverUrl: String?,
	private val sessionDelegate: ClientSessionDelegate?,
) {
	private var session: Session? = null

	fun homeserver(): String? = homeserverUrl

	/** Throws like the real SDK until a session exists from [login] or [restoreSession]. */
	fun session(): Session = session ?: throw ClientException("no session")

	suspend fun login(
		username: String,
		password: String,
		initialDeviceName: String?,
		deviceId: String?,
	) {
		FakeMatrixSdkState.loginCallCount++
		session = Session(
			accessToken = "fake-access-token",
			refreshToken = null,
			userId = "@$username:matrix.example.org",
			deviceId = "FAKEDEVICE",
			homeserverUrl = homeserverUrl.orEmpty(),
			oauthData = null,
			slidingSyncVersion = SlidingSyncVersion.NATIVE,
		)
		sessionDelegate?.saveSessionInKeychain(session!!)
	}

	suspend fun restoreSession(session: Session) {
		FakeMatrixSdkState.restoreSessionCallCount++
		this.session = session
	}

	suspend fun logout() {
		FakeMatrixSdkState.logoutCallCount++
		session = null
	}

	fun close() {
		FakeMatrixSdkState.clientCloseCount++
	}

	/**
	 * Test-only stand-in for the SDK internally refreshing an expired access token: mirrors what
	 * a real token refresh does by mutating [session] and driving [sessionDelegate]'s save
	 * callback, so a production fix that never installs the delegate leaves this update
	 * unpersisted.
	 */
	fun simulateTokenRefresh(newAccessToken: String) {
		val current = session ?: return
		val refreshed = Session(
			accessToken = newAccessToken,
			refreshToken = current.refreshToken,
			userId = current.userId,
			deviceId = current.deviceId,
			homeserverUrl = current.homeserverUrl,
			oauthData = current.oauthData,
			slidingSyncVersion = current.slidingSyncVersion,
		)
		session = refreshed
		FakeMatrixSdkState.tokenRefreshCallCount++
		sessionDelegate?.saveSessionInKeychain(refreshed)
	}
}

/**
 * Mirrors the pinned SDK's real shape: `ClientBuilder` is `AutoCloseable`, and
 * [serverName], [inMemoryStore], and [sqliteStore] each return a distinct
 * closeable wrapper rather than mutating the receiver, so a fix that only closes
 * one instance still leaks the others.
 */
class ClientBuilder : AutoCloseable {

	fun serverName(serverName: String): ClientBuilder {
		FakeMatrixSdkState.lastServerName = serverName
		return ClientBuilder()
	}

	fun inMemoryStore(): ClientBuilder {
		FakeMatrixSdkState.inMemoryStoreCalled = true
		FakeMatrixSdkState.sqliteStoreCalled = false
		return ClientBuilder()
	}

	fun homeserverUrl(homeserverUrl: String): ClientBuilder {
		FakeMatrixSdkState.lastHomeserverUrl = homeserverUrl
		return ClientBuilder()
	}

	fun sqliteStore(storeBuilder: SqliteStoreBuilder): ClientBuilder {
		FakeMatrixSdkState.sqliteStoreCalled = true
		FakeMatrixSdkState.inMemoryStoreCalled = false
		return ClientBuilder()
	}

	fun setSessionDelegate(delegate: ClientSessionDelegate): ClientBuilder {
		FakeMatrixSdkState.lastSessionDelegate = delegate
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
		if (!FakeMatrixSdkState.suspendAsynchronously) {
			return newClient()
		}
		return suspendCoroutine { continuation ->
			Thread {
				if (FakeMatrixSdkState.asyncDelayMs > 0) Thread.sleep(FakeMatrixSdkState.asyncDelayMs)
				val asyncFailure = FakeMatrixSdkState.failureToThrow
				if (asyncFailure != null && FakeMatrixSdkState.failAsynchronously) {
					continuation.resumeWithException(asyncFailure)
				} else {
					continuation.resume(newClient())
				}
			}.start()
		}
	}

	private fun newClient(): Client =
		Client(FakeMatrixSdkState.homeserverUrlToReturn, FakeMatrixSdkState.lastSessionDelegate).also {
			FakeMatrixSdkState.lastBuiltClient = it
		}
}

/** Mirrors the pinned SDK's `SqliteStoreBuilder`: also `AutoCloseable`, also wrapper-per-call. */
class SqliteStoreBuilder(dataPath: String, cachePath: String) : AutoCloseable {
	init {
		FakeMatrixSdkState.lastSqliteDataPath = dataPath
		FakeMatrixSdkState.lastSqliteCachePath = cachePath
	}

	fun passphrase(passphrase: String): SqliteStoreBuilder {
		FakeMatrixSdkState.lastPassphrase = passphrase
		return SqliteStoreBuilder(
			FakeMatrixSdkState.lastSqliteDataPath.orEmpty(),
			FakeMatrixSdkState.lastSqliteCachePath.orEmpty(),
		)
	}

	override fun close() {
		FakeMatrixSdkState.storeBuilderCloseCount++
	}
}

object FakeMatrixSdkState {
	var lastServerName: String? = null
	var inMemoryStoreCalled = false
	var sqliteStoreCalled = false
	var homeserverUrlToReturn: String? = "https://matrix.example.org"
	var failureToThrow: ClientBuildException? = null
	var failAsynchronously = false
	var suspendAsynchronously = false
	var asyncDelayMs = 0L
	var buildCallCount = 0
	var clientCloseCount = 0
	var builderCloseCount = 0
	var storeBuilderCloseCount = 0
	var lastHomeserverUrl: String? = null
	var lastSqliteDataPath: String? = null
	var lastSqliteCachePath: String? = null
	var lastPassphrase: String? = null
	var loginCallCount = 0
	var restoreSessionCallCount = 0
	var logoutCallCount = 0
	var tokenRefreshCallCount = 0
	var lastSessionDelegate: ClientSessionDelegate? = null
	var lastBuiltClient: Client? = null

	fun reset() {
		lastServerName = null
		inMemoryStoreCalled = false
		sqliteStoreCalled = false
		homeserverUrlToReturn = "https://matrix.example.org"
		failureToThrow = null
		failAsynchronously = false
		suspendAsynchronously = false
		asyncDelayMs = 0L
		buildCallCount = 0
		clientCloseCount = 0
		builderCloseCount = 0
		storeBuilderCloseCount = 0
		lastHomeserverUrl = null
		lastSqliteDataPath = null
		lastSqliteCachePath = null
		lastPassphrase = null
		loginCallCount = 0
		restoreSessionCallCount = 0
		logoutCallCount = 0
		tokenRefreshCallCount = 0
		lastSessionDelegate = null
		lastBuiltClient = null
	}
}
