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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

	private fun loggedInClient() = ReflectiveMatrixHomeserverDiscoveryClient().also {
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(ROOM))
		assertEquals(NONE, it.login("https://matrix.example.org", "alice", "swordfish"))
	}

	private companion object {
		const val ROOM = "!room:example.org"
		const val EVENT = "\$event"
	}
}
