package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.FakeTimelineEventSpec
import org.matrix.rustcomponents.sdk.MessageType

/** Simulated SDK proof for MX-058; real two-account delivery needs network/device proof. */
class ReflectiveMatrixHomeserverDiscoveryClientEditTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test
	fun `edits an own text message with replacement text content`() {
		FakeMatrixSdkState.eventItemsById = mapOf(EVENT to event(own = true))
		val client = loggedInClient()

		assertTrue(client.editTextMessage(ROOM, EVENT, "fixed"))

		assertEquals(1, FakeMatrixSdkState.editCallCount)
		assertEquals(EVENT, FakeMatrixSdkState.lastEditedEventId)
		val edited = FakeMatrixSdkState.lastEditedContent as EditedContent.RoomMessage
		assertEquals("fixed", (edited.content.messageType as MessageType.Text).content.body)
		assertEquals(0, FakeMatrixSdkState.sendCallCount)
		with(FakeMatrixSdkState) {
			assertEquals(
				listOf(1, 1, 1, 1),
				listOf(
					roomMessageEventContentCloseCount,
					eventTimelineItemDestroyCount,
					timelineCloseCount,
					roomCloseCount,
				),
			)
		}
	}

	@Test
	fun `refuses foreign, non-editable and non-text events without calling edit`() {
		FakeMatrixSdkState.eventItemsById = mapOf(
			"\$foreign" to event(own = false),
			"\$locked" to event(own = true, editable = false),
			"\$image" to event(own = true, messageType = MessageType.Image()),
			"\$redacted" to event(own = true, redacted = true),
		)
		val client = loggedInClient()

		for (id in FakeMatrixSdkState.eventItemsById.keys) {
			assertFalse(id, client.editTextMessage(ROOM, id, "fixed"))
		}

		assertEquals(0, FakeMatrixSdkState.editCallCount)
		assertEquals(0, FakeMatrixSdkState.createMessageContentCallCount)
		assertEquals(4, FakeMatrixSdkState.eventTimelineItemDestroyCount)
	}

	@Test
	fun `reports failure for unknown events, SDK errors and missing sessions`() {
		FakeMatrixSdkState.eventItemsById = mapOf(EVENT to event(own = true))
		assertFalse(ReflectiveMatrixHomeserverDiscoveryClient().editTextMessage(ROOM, EVENT, "x"))
		val client = loggedInClient()
		assertFalse(client.editTextMessage(ROOM, "\$missing", "x"))
		assertFalse(client.editTextMessage("!missing:example.org", EVENT, "x"))
		assertEquals(0, FakeMatrixSdkState.editCallCount)

		FakeMatrixSdkState.editFailureToThrow = ClientException.Generic("failed", "failed")
		assertFalse(client.editTextMessage(ROOM, EVENT, "x"))
		assertEquals(1, FakeMatrixSdkState.editCallCount)
		assertEquals(1, FakeMatrixSdkState.roomMessageEventContentCloseCount)
	}

	private fun event(
		own: Boolean,
		editable: Boolean = own,
		messageType: MessageType = MessageType.Text(),
		redacted: Boolean = false,
	) = FakeTimelineEventSpec(
		identifier = EventOrTransactionId.EventId(EVENT),
		sender = "@alice:example.org",
		body = "typo",
		timestamp = 1_000uL,
		own = own,
		messageType = messageType,
		redacted = redacted,
		editable = editable,
	)

	private fun loggedInClient() = ReflectiveMatrixHomeserverDiscoveryClient().also {
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(ROOM))
		assertEquals(NONE, it.login("https://matrix.example.org", "alice", "swordfish"))
	}

	private companion object {
		const val ROOM = "!room:example.org"
		const val EVENT = "\$event"
	}
}
