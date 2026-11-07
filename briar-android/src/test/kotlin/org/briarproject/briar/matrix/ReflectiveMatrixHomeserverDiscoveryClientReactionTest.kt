package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

	private fun loggedInClient() = ReflectiveMatrixHomeserverDiscoveryClient().also {
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(ROOM))
		assertEquals(NONE, it.login("https://matrix.example.org", "alice", "swordfish"))
	}

	private companion object {
		const val ROOM = "!room:example.org"
		const val EVENT = "\$event"
	}
}
