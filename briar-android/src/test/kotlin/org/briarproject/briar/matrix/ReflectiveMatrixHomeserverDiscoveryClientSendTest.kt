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
import org.matrix.rustcomponents.sdk.MessageType

class ReflectiveMatrixHomeserverDiscoveryClientSendTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test
	fun `sendTextMessage returns false when no session is retained`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()

		assertFalse(client.sendTextMessage("!room:example.org", "hello"))
		assertEquals(0, FakeMatrixSdkState.sendCallCount)
	}

	@Test
	fun `sendTextMessage returns false when the room cannot be found`() {
		val client = loggedInClient()

		assertFalse(client.sendTextMessage("!missing:example.org", "hello"))
		assertEquals(0, FakeMatrixSdkState.timelineCallCount)
		assertEquals(0, FakeMatrixSdkState.sendCallCount)
	}

	@Test
	fun `sendTextMessage builds a text message and fires it over the timeline`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))

		val sent = client.sendTextMessage("!room:example.org", "hello there")

		assertTrue(sent)
		assertEquals(1, FakeMatrixSdkState.createMessageContentCallCount)
		val createdMessageType = FakeMatrixSdkState.lastCreatedMessageType
		assertTrue(createdMessageType is MessageType.Text)
		assertEquals("hello there", (createdMessageType as MessageType.Text).content.body)
		assertEquals(1, FakeMatrixSdkState.sendCallCount)
		assertEquals(FakeMatrixSdkState.lastSentContent?.messageType, createdMessageType)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomMessageEventContentCloseCount)
		assertEquals(1, FakeMatrixSdkState.sendHandleCloseCount)
	}

	@Test
	fun `sendTextMessage returns false and still releases the room and timeline when send throws`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.sendFailureToThrow = ClientException.Generic("failed", "failed")

		val sent = client.sendTextMessage("!room:example.org", "hello there")

		assertFalse(sent)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomMessageEventContentCloseCount)
	}

	@Test
	fun `sendTextMessage bridges an asynchronously completed send`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.sendSuspendAsynchronously = true
		FakeMatrixSdkState.sendDelayMs = 10L

		val sent = client.sendTextMessage("!room:example.org", "hello there")

		assertTrue(sent)
		assertEquals(1, FakeMatrixSdkState.sendCallCount)
	}

	private fun loggedInClient(): ReflectiveMatrixHomeserverDiscoveryClient =
		ReflectiveMatrixHomeserverDiscoveryClient().also { client ->
			assertEquals(NONE, client.login("https://matrix.example.org", "alice", "swordfish"))
		}
}
