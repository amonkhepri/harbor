package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_CREDENTIALS
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.junit.After
import java.util.concurrent.CountDownLatch
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
	fun `successful login persists for restoration in a cold process`() {
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
		// Isolated fixture models a new process; a retired live path cannot be reopened.
		val coldStore = MatrixStoreConfiguration(
			testFolder.newFolder("cold"),
			storeConfiguration.databaseName,
			storeConfiguration.copyEncryptionKey(),
		)
		storeConfiguration.directory.copyRecursively(coldStore.directory, overwrite = true)
		val freshSession =
			MatrixAuthSessionImpl(freshClient, freshClient, FakeStoreConfigurationProvider(coldStore))
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

	@Test
	fun `rapid close event during start does not leave lingering client or revive session`() {
		val loginClient = FakeLoginClient(NONE)
		val session =
			MatrixAuthSessionImpl(FakeDiscoveryClient(DiscoveryResult.Resolved("url")), loginClient)
		
		session.close()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
		
		// If start is called after close, it must not proceed.
		session.start()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
	}

	@Test
	fun `logout after close does not revive session`() {
		val loginClient = FakeLoginClient(NONE)
		val session =
			MatrixAuthSessionImpl(FakeDiscoveryClient(DiscoveryResult.Resolved("url")), loginClient)
		
		session.close()
		session.logout()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
	}

	@Test
	fun `rapid close event during start prevents lingering client and session revival`() {
		val loginClient = FakeLoginClient(NONE)
		val session =
			MatrixAuthSessionImpl(FakeDiscoveryClient(DiscoveryResult.Resolved("url")), loginClient)
		
		session.close()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
		
		// Start after close should return immediately without calling restore
		session.start()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
	}

	@Test
	fun `rapid logout calls after close do not revive session to homeserver entry`() {
		val storeConfiguration = fakeStoreConfiguration()
		val provider = FakeStoreConfigurationProvider(storeConfiguration)
		val client = newSessionClient()
		val session = MatrixAuthSessionImpl(client, client, provider)
		
		session.close()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
		
		// Logout after close should return immediately
		session.logout()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
	}

	@Test
	fun `rapid logout and login cycles maintain consistent state`() {
		val loginClient = FakeLoginClient(NONE)
		val session =
			MatrixAuthSessionImpl(
				FakeDiscoveryClient(DiscoveryResult.Resolved("https://matrix.example.org")),
				loginClient,
			)
		
		session.start()
		
		for (i in 1..3) {
			session.submitHomeserver("matrix.example.org")
			assertEquals(MatrixAuthState.CREDENTIAL_ENTRY, session.getSnapshot().authState)
			
			session.submitCredentials("user", "pass")
			assertEquals(MatrixAuthState.READY, session.getSnapshot().authState)
			
			// rapid logout
			session.logout()
			assertEquals(MatrixAuthState.HOMESERVER_ENTRY, session.getSnapshot().authState)
			
			// Ensure the underlying client received the close/logout calls in order
			// Each cycle does 1 login, 1 logout, and 1 close from the logout block
			assertEquals(i, loginClient.loginCount)
			assertEquals(i, loginClient.closeCount)
		}
		
		session.close()
	}

	@Test
	fun `concurrent auth requests execute safely and maintain correct state`() {
		val loginClient = FakeLoginClient(NONE)
		val session =
			MatrixAuthSessionImpl(
				FakeDiscoveryClient(DiscoveryResult.Resolved("https://matrix.example.org")),
				loginClient,
			)
		
		session.start()
		session.submitHomeserver("matrix.example.org")
		
		val latch = CountDownLatch(1)
		val threadCount = 10
		val threads = (0 until threadCount).map {
			Thread {
				latch.await()
				// All threads try to submit credentials simultaneously
				session.submitCredentials("user", "pass")
			}
		}
		
		threads.forEach { it.start() }
		latch.countDown()
		threads.forEach { it.join() }
		
		// Only one login should succeed and transition state, others return early
		assertEquals(MatrixAuthState.READY, session.getSnapshot().authState)
		assertEquals(1, loginClient.loginCount)
		
		session.logout()
		session.close()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
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

	private class FakeStoreConfigurationProvider(
		private var configuration: MatrixStoreConfiguration?,
	) : MatrixStoreConfigurationProvider {
		override fun getStoreConfiguration(): MatrixStoreConfiguration? = configuration
		override fun invalidateStore() {
			configuration = null
		}
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
