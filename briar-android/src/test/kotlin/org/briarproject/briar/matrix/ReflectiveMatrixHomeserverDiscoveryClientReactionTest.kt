package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FakeTimelineEventSpec
import org.matrix.rustcomponents.sdk.Reaction
import org.matrix.rustcomponents.sdk.ReactionSenderData
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

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

	@Test
	fun `rejects concurrent duplicate reaction submission while first call is in flight`() {
		val client = loggedInClient()
		val firstCallStarted = CountDownLatch(1)
		val letFirstCallProceed = CountDownLatch(1)
		val secondCallResult = AtomicBoolean(true)
		val secondCallDone = CountDownLatch(1)

		FakeMatrixSdkState.onToggleReaction = {
			firstCallStarted.countDown()
			letFirstCallProceed.await(5, TimeUnit.SECONDS)
		}

		val executor = Executors.newFixedThreadPool(2)
		try {
			val firstCallFuture = executor.submit(
				Callable {
					client.sendReaction(ROOM, EVENT, "👍")
				},
			)

			assertTrue("First call should reach SDK", firstCallStarted.await(5, TimeUnit.SECONDS))

			// Second overlapping call for the exact same (room, event, reaction)
			executor.submit {
				secondCallResult.set(client.sendReaction(ROOM, EVENT, "👍"))
				secondCallDone.countDown()
			}

			assertTrue("Second call should finish promptly", secondCallDone.await(5, TimeUnit.SECONDS))
			assertFalse("Duplicate submission while in flight must be rejected", secondCallResult.get())

			letFirstCallProceed.countDown()
			assertTrue("First call should succeed", firstCallFuture.get(5, TimeUnit.SECONDS))

			assertEquals(
				"SDK toggleReaction must be called exactly once",
				1,
				FakeMatrixSdkState.toggleReactionCallCount,
			)
		} finally {
			letFirstCallProceed.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `permits subsequent reaction submission after first call completes`() {
		val client = loggedInClient()
		assertTrue(client.sendReaction(ROOM, EVENT, "👍"))
		assertTrue(client.sendReaction(ROOM, EVENT, "👍"))
		assertEquals(2, FakeMatrixSdkState.toggleReactionCallCount)
	}

	@Test
	fun `retains duplicate protection after timeout until native operation completes`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient(discoveryTimeoutMs = 50L).also {
			FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(ROOM))
			assertEquals(NONE, it.login("https://matrix.example.org", "alice", "swordfish"))
		}

		FakeMatrixSdkState.toggleReactionSuspendAsynchronously = true
		FakeMatrixSdkState.toggleReactionCancellable = false
		val nativeContinuation = AtomicReference<kotlin.coroutines.Continuation<Boolean>>()
		val nativeStarted = CountDownLatch(1)

		FakeMatrixSdkState.onToggleReactionContinuation = { cont ->
			nativeContinuation.set(cont)
			nativeStarted.countDown()
		}

		val firstCallSuccess = client.sendReaction(ROOM, EVENT, "👍")
		assertFalse("First call must report false on timeout", firstCallSuccess)
		assertEquals(1, FakeMatrixSdkState.toggleReactionCallCount)

		// Second call (immediate retry) must be rejected because native operation is still unresolved
		assertFalse(
			"Retry while native operation is still running after timeout must be rejected",
			client.sendReaction(ROOM, EVENT, "👍"),
		)
		assertEquals(
			"SDK must not have received second call",
			1,
			FakeMatrixSdkState.toggleReactionCallCount,
		)

		// Now simulate native operation finishing late
		val cont = nativeContinuation.get()
		assertNotNull("Native continuation must be captured", cont)
		cont.resume(true)

		// Third call (retry after native completion) must now be permitted
		FakeMatrixSdkState.toggleReactionSuspendAsynchronously = false
		assertTrue(
			"Subsequent call after native completion must succeed",
			client.sendReaction(ROOM, EVENT, "👍"),
		)
		assertEquals("SDK should receive subsequent call", 2, FakeMatrixSdkState.toggleReactionCallCount)
	}

	@Test
	fun `cancels suspended SDK operation on timeout`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient(discoveryTimeoutMs = 50L).also {
			FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(ROOM))
			assertEquals(NONE, it.login("https://matrix.example.org", "alice", "swordfish"))
		}

		FakeMatrixSdkState.toggleReactionSuspendAsynchronously = true
		FakeMatrixSdkState.toggleReactionCancellable = true
		val nativeStarted = CountDownLatch(1)

		FakeMatrixSdkState.onToggleReactionContinuation = { _ ->
			nativeStarted.countDown()
		}

		val success = client.sendReaction(ROOM, EVENT, "👍")
		assertFalse("Call must report false on timeout", success)
		assertTrue(
			"SDK operation must be cancelled on timeout",
			FakeMatrixSdkState.toggleReactionCancelled,
		)

		// Cancellation resumes and cleans up duplicate protection, permitting subsequent call
		FakeMatrixSdkState.toggleReactionSuspendAsynchronously = false
		assertTrue(
			"Subsequent call after cancelled operation must succeed",
			client.sendReaction(ROOM, EVENT, "👍"),
		)
		assertEquals(2, FakeMatrixSdkState.toggleReactionCallCount)
	}

	@Test
	fun `removeReaction removes own reaction and leaves others intact`() {
		val client = loggedInClient()
		val alice = "@alice:matrix.example.org"
		val bob = "@bob:matrix.example.org"

		// Event has reactions: 👍 from alice and bob, ❤️ only from bob
		FakeMatrixSdkState.eventItemsById = mapOf(
			EVENT to FakeTimelineEventSpec(
				identifier = EventOrTransactionId.EventId(EVENT),
				sender = bob,
				body = "Hello",
				timestamp = 1000uL,
				reactions = listOf(
					Reaction("👍", listOf(ReactionSenderData(alice), ReactionSenderData(bob))),
					Reaction("❤️", listOf(ReactionSenderData(bob))),
				),
			),
		)

		// Removing alice's 👍 succeeds
		assertTrue(client.removeReaction(ROOM, EVENT, "👍"))
		assertEquals(1, FakeMatrixSdkState.toggleReactionCallCount)

		// Aggregate count reconciles with remote state: 👍 count is now 1 (only bob), ❤️ intact
		val updatedEvent = FakeMatrixSdkState.eventItemsById[EVENT]!!
		val thumbsUp = updatedEvent.reactions.first { it.key == "👍" }
		assertEquals(1, thumbsUp.senders.size)
		assertEquals(bob, thumbsUp.senders[0].senderId)
		val heart = updatedEvent.reactions.first { it.key == "❤️" }
		assertEquals(1, heart.senders.size)
		assertEquals(bob, heart.senders[0].senderId)
	}

	@Test
	fun `removeReaction fails when user does not own the reaction without calling SDK`() {
		val client = loggedInClient()
		val bob = "@bob:matrix.example.org"

		// Event has reaction from bob only
		FakeMatrixSdkState.eventItemsById = mapOf(
			EVENT to FakeTimelineEventSpec(
				identifier = EventOrTransactionId.EventId(EVENT),
				sender = bob,
				body = "Hello",
				timestamp = 1000uL,
				reactions = listOf(
					Reaction("👍", listOf(ReactionSenderData(bob))),
				),
			),
		)

		// Alice attempting to remove 👍 is rejected without calling SDK toggleReaction
		assertFalse(client.removeReaction(ROOM, EVENT, "👍"))
		assertEquals(0, FakeMatrixSdkState.toggleReactionCallCount)

		// Alice attempting to remove unreacted emoji is rejected
		assertFalse(client.removeReaction(ROOM, EVENT, "❤️"))
		assertEquals(0, FakeMatrixSdkState.toggleReactionCallCount)
	}

	@Test
	fun `removeReaction rejects blank inputs and unknown events`() {
		val client = loggedInClient()

		assertFalse(client.removeReaction(ROOM, EVENT, ""))
		assertFalse(client.removeReaction(ROOM, EVENT, "   "))
		assertFalse(client.removeReaction(ROOM, "", "👍"))
		assertFalse(client.removeReaction("", EVENT, "👍"))
		assertFalse(client.removeReaction(ROOM, "unknown-event", "👍"))
		assertEquals(0, FakeMatrixSdkState.toggleReactionCallCount)
	}

	@Test
	fun `removeReaction protects against concurrent duplicate in-flight removals`() {
		val client = loggedInClient()
		val alice = "@alice:matrix.example.org"
		FakeMatrixSdkState.eventItemsById = mapOf(
			EVENT to FakeTimelineEventSpec(
				identifier = EventOrTransactionId.EventId(EVENT),
				sender = "@bob:matrix.example.org",
				body = "Hello",
				timestamp = 1000uL,
				reactions = listOf(
					Reaction("👍", listOf(ReactionSenderData(alice))),
				),
			),
		)

		val firstCallStarted = CountDownLatch(1)
		val letFirstCallProceed = CountDownLatch(1)
		val secondCallResult = AtomicBoolean(true)
		val secondCallDone = CountDownLatch(1)

		FakeMatrixSdkState.onToggleReaction = {
			firstCallStarted.countDown()
			letFirstCallProceed.await(5, TimeUnit.SECONDS)
		}

		val executor = Executors.newFixedThreadPool(2)
		try {
			val firstCallFuture = executor.submit(
				Callable {
					client.removeReaction(ROOM, EVENT, "👍")
				},
			)

			assertTrue("First call should reach SDK", firstCallStarted.await(5, TimeUnit.SECONDS))

			// Second overlapping call for the exact same (room, event, reaction)
			executor.submit {
				secondCallResult.set(client.removeReaction(ROOM, EVENT, "👍"))
				secondCallDone.countDown()
			}

			assertTrue("Second call should finish promptly", secondCallDone.await(5, TimeUnit.SECONDS))
			assertFalse("Duplicate removal while in flight must be rejected", secondCallResult.get())

			letFirstCallProceed.countDown()
			assertTrue("First call must succeed", firstCallFuture.get(5, TimeUnit.SECONDS))
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `tracks SDK ownership for own and peer reactions when reading timeline`() {
		val alice = "@alice:matrix.example.org"
		val bob = "@bob:matrix.example.org"
		val client = ReflectiveMatrixHomeserverDiscoveryClient().also {
			FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(ROOM))
			FakeMatrixSdkState.timelineDiffsToReturn = listOf(
				TimelineDiff.Reset(
					listOf(
						TimelineItem(
							FakeTimelineEventSpec(
								identifier = EventOrTransactionId.EventId(EVENT),
								sender = bob,
								body = "Hello",
								timestamp = 1000uL,
								reactions = listOf(
									Reaction("👍", listOf(ReactionSenderData(alice), ReactionSenderData(bob))),
									Reaction("❤️", listOf(ReactionSenderData(bob))),
								),
							),
						),
					),
				),
			)
			assertEquals(NONE, it.login("https://matrix.example.org", "alice", "swordfish"))
		}

		val items = client.getRecentMessages(ROOM, 50)
		assertNotNull(items)
		assertEquals(1, items!!.size)
		val msg = items[0]
		assertEquals(2, msg.reactions.size)

		val thumbsUp = msg.reactions.first { it.key == "👍" }
		assertEquals(2, thumbsUp.count)
		assertTrue("Alice's reaction must have isOwn=true", thumbsUp.isOwn)

		val heart = msg.reactions.first { it.key == "❤️" }
		assertEquals(1, heart.count)
		assertFalse("Bob's reaction must have isOwn=false for Alice", heart.isOwn)
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
