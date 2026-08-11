package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_HOMESERVER
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import java.util.concurrent.TimeUnit

class ReflectiveMatrixHomeserverDiscoveryClientTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

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
