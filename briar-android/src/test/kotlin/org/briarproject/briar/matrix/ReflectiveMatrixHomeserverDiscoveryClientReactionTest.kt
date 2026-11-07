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

/** Simulated SDK proof for MX-059; real two-account delivery needs network/device proof. */
class ReflectiveMatrixHomeserverDiscoveryClientReactionTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test
	fun `sends a reaction to an event and delegates to Timeline toggleReaction`() {
		val client = loggedInClient()

		assertTrue(client.sendReaction(ROOM, EVENT, "👍"))

		assertEquals(1, FakeMatrixSdkState.toggleReactionCallCount)
		assertEquals(EVENT, FakeMatrixSdkState.lastReactionEventId)
		assertEquals("👍", FakeMatrixSdkState.lastReactionKey)
		with(FakeMatrixSdkState) {
			assertEquals(1, timelineCloseCount)
			assertEquals(1, roomCloseCount)
		}
	}

	@Test
	fun `refuses blank reactions or blank event IDs without calling SDK`() {
		val client = loggedInClient()

		assertFalse(client.sendReaction(ROOM, EVENT, ""))
		assertFalse(client.sendReaction(ROOM, EVENT, "   "))
		assertFalse(client.sendReaction(ROOM, "", "👍"))
		assertFalse(client.sendReaction(ROOM, "   ", "👍"))

		assertEquals(0, FakeMatrixSdkState.toggleReactionCallCount)
	}

	@Test
	fun `reports failure for unknown rooms, missing sessions and SDK errors`() {
		assertFalse(ReflectiveMatrixHomeserverDiscoveryClient().sendReaction(ROOM, EVENT, "👍"))
		val client = loggedInClient()
		assertFalse(client.sendReaction("!missing:example.org", EVENT, "👍"))
		assertEquals(0, FakeMatrixSdkState.toggleReactionCallCount)

		FakeMatrixSdkState.toggleReactionFailureToThrow = ClientException.Generic("failed", "failed")
		assertFalse(client.sendReaction(ROOM, EVENT, "👍"))
		assertEquals(1, FakeMatrixSdkState.toggleReactionCallCount)
	}

	private fun loggedInClient() = ReflectiveMatrixHomeserverDiscoveryClient().also {
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(ROOM))
		assertEquals(NONE, it.login("https://matrix.example.org", "alice", "swordfish"))
	}

	private companion object {
		const val ROOM = "!room:example.org"
		const val EVENT = "\$event"
	}
}
