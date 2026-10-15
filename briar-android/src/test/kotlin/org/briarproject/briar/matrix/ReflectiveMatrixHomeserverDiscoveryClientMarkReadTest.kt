package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.ReceiptType

class ReflectiveMatrixHomeserverDiscoveryClientMarkReadTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test
	fun `markRead returns false when no session is retained`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()

		assertFalse(client.markRead("!room:example.org"))
		assertEquals(0, FakeMatrixSdkState.markAsReadCallCount)
	}

	@Test
	fun `markRead returns false when the room cannot be found`() {
		val client = loggedInClient()

		assertFalse(client.markRead("!missing:example.org"))
		assertEquals(0, FakeMatrixSdkState.markAsReadCallCount)
	}

	@Test
	fun `markRead sends a private READ receipt and releases the room`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))

		val marked = client.markRead("!room:example.org")

		assertTrue(marked)
		assertEquals(1, FakeMatrixSdkState.markAsReadCallCount)
		assertEquals("!room:example.org", FakeMatrixSdkState.lastMarkAsReadRoomId)
		assertEquals(ReceiptType.READ_PRIVATE, FakeMatrixSdkState.lastMarkAsReadReceiptType)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
	}

	@Test
	fun `markRead returns false and still releases the room when the SDK call throws`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.markAsReadFailureToThrow = ClientException.Generic("failed", "failed")

		val marked = client.markRead("!room:example.org")

		assertFalse(marked)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
	}

	private fun loggedInClient(): ReflectiveMatrixHomeserverDiscoveryClient =
		ReflectiveMatrixHomeserverDiscoveryClient().also { client ->
			assertEquals(NONE, client.login("https://matrix.example.org", "alice", "swordfish"))
		}
}
