package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_HOMESERVER
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.briarproject.briar.matrix.MatrixLoginClient.RestoreResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import java.io.File
import java.lang.reflect.UndeclaredThrowableException
import java.util.Collections
import java.util.concurrent.TimeUnit

class ReflectiveMatrixHomeserverDiscoveryClientTest {

	@get:Rule
	val testFolder = TemporaryFolder()

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test
	fun `login writes an encrypted atomic sidecar with no leftover temp file`() {
		val storeConfiguration = fakeStoreConfiguration()
		val client = ReflectiveMatrixHomeserverDiscoveryClient()

		val result = client.login("https://matrix.example.org", "alice", "swordfish", storeConfiguration)

		assertEquals(NONE, result)
		val sessionFile = File(storeConfiguration.directory, "session")
		assertTrue(sessionFile.isFile)
		val siblingFiles = storeConfiguration.directory.listFiles().orEmpty().map { it.name }
		assertFalse(
			"no temp file should survive a completed atomic write",
			siblingFiles.any {
				it.startsWith("session-") &&
					it != "session"
			},
		)
		val sidecarBytes = sessionFile.readBytes().toList()
		val plaintextNeedle = "fake-access-token".toByteArray().toList()
		assertFalse(
			"sidecar must not contain the plaintext access token",
			Collections.indexOfSubList(sidecarBytes, plaintextNeedle) >= 0,
		)
	}

	@Test
	fun `restore after a simulated refresh returns the refreshed token, not the login-time token`() {
		val storeConfiguration = fakeStoreConfiguration()
		val client = ReflectiveMatrixHomeserverDiscoveryClient()
		assertEquals(
			NONE,
			client.login("https://matrix.example.org", "alice", "swordfish", storeConfiguration),
		)

		val loggedInClient = FakeMatrixSdkState.lastBuiltClient!!
		loggedInClient.simulateTokenRefresh("refreshed-access-token")
		assertEquals(1, FakeMatrixSdkState.tokenRefreshCallCount)

		val freshClient = ReflectiveMatrixHomeserverDiscoveryClient()
		val restoreResult = freshClient.restore(storeConfiguration)

		assertEquals(RestoreResult.Restored("https://matrix.example.org"), restoreResult)
		assertEquals("refreshed-access-token", FakeMatrixSdkState.lastBuiltClient!!.session().accessToken)
	}

	@Test
	fun `restore rejects a sidecar tampered after encryption instead of trusting it`() {
		val storeConfiguration = fakeStoreConfiguration()
		val client = ReflectiveMatrixHomeserverDiscoveryClient()
		assertEquals(
			NONE,
			client.login("https://matrix.example.org", "alice", "swordfish", storeConfiguration),
		)
		val sessionFile = File(storeConfiguration.directory, "session")
		val tampered = sessionFile.readBytes()
		tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
		sessionFile.writeBytes(tampered)

		val freshClient = ReflectiveMatrixHomeserverDiscoveryClient()
		val restoreResult = freshClient.restore(storeConfiguration)

		assertEquals(RestoreResult.NotFound, restoreResult)
	}

	@Test
	fun `restore against the wrong store key rejects the sidecar instead of using a stale key`() {
		val originalConfiguration = fakeStoreConfiguration()
		val client = ReflectiveMatrixHomeserverDiscoveryClient()
		assertEquals(
			NONE,
			client.login("https://matrix.example.org", "alice", "swordfish", originalConfiguration),
		)
		val wrongKeyConfiguration = MatrixStoreConfiguration(
			originalConfiguration.directory,
			originalConfiguration.databaseName,
			ByteArray(32) { (it + 1).toByte() },
		)

		val freshClient = ReflectiveMatrixHomeserverDiscoveryClient()
		val restoreResult = freshClient.restore(wrongKeyConfiguration)

		assertEquals(RestoreResult.NotFound, restoreResult)
	}

	// The pinned AAR's `ClientSessionDelegate.retrieveSessionFromKeychain` bytecode declares no
	// `throws` clause, so the JDK's `Proxy` dispatcher wraps our checked `ClientException` throw
	// into `UndeclaredThrowableException` before it reaches the caller; these tests assert that
	// real, observed shape (checked exception with a real `ClientException` cause) rather than a
	// bare `ClientException`, which only the pre-fix fake's incorrect `@Throws` annotation made
	// look reachable. See `SessionDelegateInvocationHandler`'s KDoc for the full explanation.

	@Test
	fun `session delegate retrieval fails closed instead of null for a missing sidecar`() {
		val storeConfiguration = fakeStoreConfiguration()
		val client = ReflectiveMatrixHomeserverDiscoveryClient()
		assertEquals(
			NONE,
			client.login("https://matrix.example.org", "alice", "swordfish", storeConfiguration),
		)
		val delegate = FakeMatrixSdkState.lastSessionDelegate!!
		assertTrue(File(storeConfiguration.directory, "session").delete())

		val thrown = assertThrows(UndeclaredThrowableException::class.java) {
			delegate.retrieveSessionFromKeychain("@alice:matrix.example.org")
		}
		assertTrue(thrown.cause is ClientException)
	}

	@Test
	fun `session delegate retrieval fails closed instead of null for a corrupt sidecar`() {
		val storeConfiguration = fakeStoreConfiguration()
		val client = ReflectiveMatrixHomeserverDiscoveryClient()
		assertEquals(
			NONE,
			client.login("https://matrix.example.org", "alice", "swordfish", storeConfiguration),
		)
		val delegate = FakeMatrixSdkState.lastSessionDelegate!!
		val sessionFile = File(storeConfiguration.directory, "session")
		val tampered = sessionFile.readBytes()
		tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
		sessionFile.writeBytes(tampered)

		val thrown = assertThrows(UndeclaredThrowableException::class.java) {
			delegate.retrieveSessionFromKeychain("@alice:matrix.example.org")
		}
		assertTrue(thrown.cause is ClientException)
	}

	@Test
	fun `compiled client never references java-nio API 26-only Files or Path types`() {
		val classBytes = ReflectiveMatrixHomeserverDiscoveryClient::class.java
			.getResourceAsStream("ReflectiveMatrixHomeserverDiscoveryClient.class")!!
			.use { it.readBytes() }
		// Constant-pool UTF8 entries for referenced class names appear as contiguous ASCII
		// bytes, so a raw substring search over the class file's ISO-8859-1 text is enough to
		// catch a `java.nio.file.Files`/`Path` reference reappearing without needing a class
		// file parser; Harbor's minSdk is 21 and those types are Android API 26+.
		val text = String(classBytes, Charsets.ISO_8859_1)
		assertFalse(
			"must not reference java/nio/file/Files (Android API 26+) on minSdk 21",
			text.contains("java/nio/file/Files"),
		)
		assertFalse(
			"must not reference java/nio/file/Path (Android API 26+) on minSdk 21",
			text.contains("java/nio/file/Path"),
		)
	}

	private fun fakeStoreConfiguration(): MatrixStoreConfiguration = MatrixStoreConfiguration(
		testFolder.newFolder("matrix-sdk"),
		"matrix-sdk",
		ByteArray(32) { it.toByte() },
	)

	@Test
	fun `synchronous success resolves the SDK homeserver url and closes the client and builders`() {
		FakeMatrixSdkState.homeserverUrlToReturn = "https://matrix.example.org"

		val result = ReflectiveMatrixHomeserverDiscoveryClient().discover("example.org")

		assertEquals(DiscoveryResult.Resolved("https://matrix.example.org"), result)
		assertEquals("example.org", FakeMatrixSdkState.lastServerName)
		assertEquals(true, FakeMatrixSdkState.inMemoryStoreCalled)
		assertEquals(1, FakeMatrixSdkState.clientCloseCount)
		// The initial builder plus the distinct wrappers `serverName()` and
		// `inMemoryStore()` each return: three closeable builders total.
		assertEquals(3, FakeMatrixSdkState.builderCloseCount)
	}

	@Test
	fun `asynchronous success on another thread resolves the url and closes builders`() {
		FakeMatrixSdkState.homeserverUrlToReturn = "https://matrix.example.org"
		FakeMatrixSdkState.suspendAsynchronously = true
		FakeMatrixSdkState.asyncDelayMs = 50L

		val result = ReflectiveMatrixHomeserverDiscoveryClient().discover("example.org")

		assertEquals(DiscoveryResult.Resolved("https://matrix.example.org"), result)
		assertEquals(1, FakeMatrixSdkState.clientCloseCount)
		assertEquals(3, FakeMatrixSdkState.builderCloseCount)
	}

	@Test
	fun `synchronous invalid server name failure maps to invalid homeserver`() {
		FakeMatrixSdkState.failureToThrow = ClientBuildException.InvalidServerName()

		val result = ReflectiveMatrixHomeserverDiscoveryClient().discover("not a server name")

		assertEquals(DiscoveryResult.Failed(INVALID_HOMESERVER), result)
		assertEquals(0, FakeMatrixSdkState.clientCloseCount)
	}

	@Test
	fun `asynchronous server unreachable failure maps to discovery failed`() {
		FakeMatrixSdkState.suspendAsynchronously = true
		FakeMatrixSdkState.failAsynchronously = true
		FakeMatrixSdkState.failureToThrow = ClientBuildException.ServerUnreachable()

		val result = ReflectiveMatrixHomeserverDiscoveryClient().discover("unreachable.example.org")

		assertEquals(DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED), result)
		assertEquals(0, FakeMatrixSdkState.clientCloseCount)
	}

	@Test
	fun `blank resolved homeserver url is treated as discovery failed`() {
		FakeMatrixSdkState.homeserverUrlToReturn = ""

		val result = ReflectiveMatrixHomeserverDiscoveryClient().discover("example.org")

		assertEquals(DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED), result)
	}

	@Test
	fun `slow asynchronous build times out as discovery failed instead of hanging`() {
		FakeMatrixSdkState.suspendAsynchronously = true
		FakeMatrixSdkState.asyncDelayMs = 500L

		val result = ReflectiveMatrixHomeserverDiscoveryClient(
			discoveryTimeoutMs = 50L,
		).discover("example.org")

		assertEquals(DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED), result)
	}

	@Test
	fun `client delivered after the adapter times out is still closed`() {
		FakeMatrixSdkState.suspendAsynchronously = true
		FakeMatrixSdkState.asyncDelayMs = 300L
		FakeMatrixSdkState.homeserverUrlToReturn = "https://matrix.example.org"

		val result = ReflectiveMatrixHomeserverDiscoveryClient(
			discoveryTimeoutMs = 50L,
		).discover("example.org")

		assertEquals(DiscoveryResult.Failed(HOMESERVER_DISCOVERY_FAILED), result)
		// The client has not arrived yet at the moment discover() gives up.
		assertEquals(0, FakeMatrixSdkState.clientCloseCount)

		awaitCondition(timeoutMs = 2_000L) { FakeMatrixSdkState.clientCloseCount == 1 }
		assertEquals(1, FakeMatrixSdkState.clientCloseCount)
	}

	/** Polls until [condition] holds or [timeoutMs] elapses, for asserting on the fake's background completion thread. */
	private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
		while (!condition() && System.nanoTime() < deadline) Thread.sleep(10L)
	}
}
