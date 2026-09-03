package org.briarproject.briar.api.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixHomeserverDiscoveryClientShapeTest {

	@Test
	fun `resolved result carries the resolved homeserver url`() {
		val client = FakeMatrixHomeserverDiscoveryClient(
			DiscoveryResult.Resolved("https://matrix.example.org"),
		)

		val result = client.discover("example.org")

		assertEquals(DiscoveryResult.Resolved("https://matrix.example.org"), result)
	}

	@Test
	fun `failed result carries a recoverable error detail`() {
		val client = FakeMatrixHomeserverDiscoveryClient(
			DiscoveryResult.Failed(RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED),
		)

		val result = client.discover("unreachable.example.org")

		assertEquals(DiscoveryResult.Failed(RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED), result)
	}

	@Test
	fun `client receives the exact server name passed to discover`() {
		val client = FakeMatrixHomeserverDiscoveryClient(
			DiscoveryResult.Resolved("https://matrix.example.org"),
		)

		client.discover("example.org")

		assertEquals("example.org", client.lastRequestedServerName)
	}

	private class FakeMatrixHomeserverDiscoveryClient(private val result: DiscoveryResult) :
		MatrixHomeserverDiscoveryClient {
		var lastRequestedServerName: String? = null
			private set

		override fun discover(serverName: String): DiscoveryResult {
			lastRequestedServerName = serverName
			return result
		}
	}
}
