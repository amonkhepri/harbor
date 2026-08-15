package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_CREDENTIALS
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState

class MatrixAuthSessionImplTest {

	@get:Rule
	val testFolder = TemporaryFolder()

	@After
	fun tearDown() = FakeMatrixSdkState.reset()

	@Test
	fun `resolved homeserver login and logout drive state and close SDK client`() {
		val client = newSessionClient()
		val session = MatrixAuthSessionImpl(client, client)

		session.start()
		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, session.getSnapshot().authState)
		session.submitHomeserver("matrix.invalid")
		assertEquals(MatrixAuthState.CREDENTIAL_ENTRY, session.getSnapshot().authState)
		session.submitCredentials("", "")
		assertEquals(MatrixAuthState.READY, session.getSnapshot().authState)
		assertEquals("https://matrix.example.org", FakeMatrixSdkState.lastHomeserverUrl)
		assertEquals(1, FakeMatrixSdkState.loginCallCount)

		session.logout()

		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, session.getSnapshot().authState)
		assertEquals(1, FakeMatrixSdkState.logoutCallCount)
		assertEquals(2, FakeMatrixSdkState.clientCloseCount)
	}

	@Test
	fun `credential failure is recoverable and retains resolved homeserver`() {
		val loginClient = FakeLoginClient(INVALID_CREDENTIALS)
		val session = MatrixAuthSessionImpl(
			FakeDiscoveryClient(DiscoveryResult.Resolved("https://matrix.invalid")),
			loginClient,
		)

		session.start()
		session.submitHomeserver("matrix.invalid")
		session.submitCredentials("", "")

		val snapshot = session.getSnapshot()
		assertEquals(MatrixAuthState.RECOVERABLE_ERROR, snapshot.authState)
		assertEquals(INVALID_CREDENTIALS, snapshot.errorDetail)
		assertEquals("https://matrix.invalid", snapshot.homeserverUrl)
		assertEquals(1, loginClient.loginCount)
	}

	@Test
	fun `discovery failure stays before credential entry and close ends session`() {
		val loginClient = FakeLoginClient(NONE)
		val session = MatrixAuthSessionImpl(
			FakeDiscoveryClient(DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED)),
			loginClient,
		)

		session.start()
		session.submitHomeserver("matrix.invalid")

		assertEquals(MatrixAuthState.RECOVERABLE_ERROR, session.getSnapshot().authState)
		assertNull(session.getSnapshot().homeserverUrl)
		session.close()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
		assertEquals(1, loginClient.closeCount)
	}

	@Test
	fun `successful login persists so a fresh session instance restores without re-authenticating`() {
		val storeConfiguration = fakeStoreConfiguration()
		val provider = FakeStoreConfigurationProvider(storeConfiguration)
		val client = newSessionClient()
		val session = MatrixAuthSessionImpl(client, client, provider)

		session.start()
		session.submitHomeserver("matrix.invalid")
		session.submitCredentials("alice", "swordfish")
		assertEquals(MatrixAuthState.READY, session.getSnapshot().authState)
		session.close()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)

		val freshClient = newSessionClient()
		val freshSession = MatrixAuthSessionImpl(freshClient, freshClient, provider)
		freshSession.start()

		val snapshot = freshSession.getSnapshot()
		assertEquals(MatrixAuthState.READY, snapshot.authState)
		assertEquals("https://matrix.example.org", snapshot.homeserverUrl)
	}

	@Test
	fun `logout invalidates the persisted session for a fresh instance`() {
		val storeConfiguration = fakeStoreConfiguration()
		val provider = FakeStoreConfigurationProvider(storeConfiguration)
		val client = newSessionClient()
		val session = MatrixAuthSessionImpl(client, client, provider)
		session.start()
		session.submitHomeserver("matrix.invalid")
		session.submitCredentials("alice", "swordfish")

		session.logout()
		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, session.getSnapshot().authState)

		val freshClient = newSessionClient()
		val freshSession = MatrixAuthSessionImpl(freshClient, freshClient, provider)
		freshSession.start()

		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, freshSession.getSnapshot().authState)
	}

	@Test
	fun `close never persists nor clears a session so a fresh instance stays at homeserver entry`() {
		val storeConfiguration = fakeStoreConfiguration()
		val provider = FakeStoreConfigurationProvider(storeConfiguration)
		val client = newSessionClient()
		val session = MatrixAuthSessionImpl(client, client, provider)

		session.start()
		session.close()

		val freshSession = MatrixAuthSessionImpl(client, client, provider)
		freshSession.start()

		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, freshSession.getSnapshot().authState)
	}

	private fun fakeStoreConfiguration(): MatrixStoreConfiguration = MatrixStoreConfiguration(
		testFolder.newFolder("matrix-sdk"),
		"matrix-sdk",
		ByteArray(32) { it.toByte() },
	)

	private fun newSessionClient(): ReflectiveMatrixHomeserverDiscoveryClient =
		ReflectiveMatrixHomeserverDiscoveryClient(
			sessionDelegateFactory = DirectMatrixClientSessionDelegateFactory(),
		)

	private class FakeStoreConfigurationProvider(private val configuration: MatrixStoreConfiguration) :
		MatrixStoreConfigurationProvider {
		override fun getStoreConfiguration(): MatrixStoreConfiguration = configuration
	}

	private class FakeDiscoveryClient(private val result: DiscoveryResult) :
		MatrixHomeserverDiscoveryClient {
		override fun discover(serverName: String): DiscoveryResult = result
	}

	private class FakeLoginClient(private val result: RecoverableErrorDetail) : MatrixLoginClient {
		var loginCount = 0
		var closeCount = 0
		override fun login(
			homeserverUrl: String,
			username: String,
			password: String,
			storeConfiguration: MatrixStoreConfiguration?,
		) = result.also { loginCount++ }
		override fun restore(storeConfiguration: MatrixStoreConfiguration) =
			MatrixLoginClient.RestoreResult.NotFound
		override fun logout() = Unit
		override fun close() {
			closeCount++
		}
	}
}
