package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixDeviceTrustState
import org.briarproject.briar.api.matrix.MatrixVerificationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

class MatrixDeviceVerificationTest {
	private val client1 = ReflectiveMatrixHomeserverDiscoveryClient()
	private val client2 = ReflectiveMatrixHomeserverDiscoveryClient()

	@Before fun setUp() {
		FakeMatrixSdkState.reset()
	}

	@After fun reset() {
		client1.close()
		client2.close()
		FakeMatrixSdkState.reset()
	}

	private fun loginBoth() {
		assertEquals(NONE, client1.login("https://matrix.example.org", "alice", "password"))
		assertEquals(NONE, client2.login("https://matrix.example.org", "alice", "password"))
	}

	@Test fun `two disposable clients verify matching values and both confirm marks device trusted`() {
		loginBoth()
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client1.getDeviceTrustState())
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client2.getDeviceTrustState())

		val state1 = AtomicReference<MatrixVerificationState>()
		val state2 = AtomicReference<MatrixVerificationState>()
		val latch1Comparing = CountDownLatch(1)
		val latch2Comparing = CountDownLatch(1)
		val latch1Verified = CountDownLatch(1)
		val latch2Verified = CountDownLatch(1)

		val handle2 = client2.startVerification({ state ->
			state2.set(state)
			if (state is MatrixVerificationState.Comparing) latch2Comparing.countDown()
			if (state is MatrixVerificationState.Verified) latch1Verified.countDown()
		}, 10_000L)

		val handle1 = client1.startVerification({ state ->
			state1.set(state)
			if (state is MatrixVerificationState.Comparing) latch1Comparing.countDown()
			if (state is MatrixVerificationState.Verified) latch2Verified.countDown()
		}, 10_000L)

		assertNotNull(handle1)
		assertNotNull(handle2)
		assertTrue(FakeMatrixSdkState.syncServiceStartCalls >= 2)

		assertTrue(latch1Comparing.await(5, SECONDS))
		assertTrue(latch2Comparing.await(5, SECONDS))
		assertTrue(FakeMatrixSdkState.verificationAcknowledgeCalls >= 1)

		val comparing1 = state1.get() as MatrixVerificationState.Comparing
		val comparing2 = state2.get() as MatrixVerificationState.Comparing
		assertEquals(comparing1.emojis, comparing2.emojis)
		assertTrue(comparing1.emojis.isNotEmpty())

		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client1.getDeviceTrustState())
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client2.getDeviceTrustState())

		handle1!!.confirm()
		handle2!!.confirm()

		assertTrue(latch1Verified.await(5, SECONDS))
		assertTrue(latch2Verified.await(5, SECONDS))
		assertEquals(MatrixVerificationState.Verified, state1.get())
		assertEquals(MatrixVerificationState.Verified, state2.get())

		assertEquals(MatrixDeviceTrustState.VERIFIED, client1.getDeviceTrustState())
		assertEquals(MatrixDeviceTrustState.VERIFIED, client2.getDeviceTrustState())
		val deadline = System.currentTimeMillis() + 5000
		while (FakeMatrixSdkState.syncServiceStopCalls < 2 && System.currentTimeMillis() < deadline) {
			Thread.sleep(10)
		}
		assertTrue(FakeMatrixSdkState.syncServiceStopCalls >= 2)
	}

	@Test fun `empty verification data fails session and never marks device trusted`() {
		FakeMatrixSdkState.defaultVerificationEmojis = emptyList()
		loginBoth()
		val state1 = AtomicReference<MatrixVerificationState>()
		val state2 = AtomicReference<MatrixVerificationState>()
		val latch1Terminal = CountDownLatch(1)
		val latch2Terminal = CountDownLatch(1)

		val handle2 = client2.startVerification({ state ->
			state2.set(state)
			if (state is MatrixVerificationState.Failed || state is MatrixVerificationState.Cancelled) {
				latch2Terminal.countDown()
			}
		}, 10_000L)

		val handle1 = client1.startVerification({ state ->
			state1.set(state)
			if (state is MatrixVerificationState.Failed || state is MatrixVerificationState.Cancelled) {
				latch1Terminal.countDown()
			}
		}, 10_000L)

		assertNotNull(handle1)
		assertNotNull(handle2)
		assertTrue(latch1Terminal.await(5, SECONDS))
		assertTrue(latch2Terminal.await(5, SECONDS))

		assertEquals(MatrixVerificationState.Failed, state1.get())
		assertEquals(MatrixVerificationState.Failed, state2.get())
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client1.getDeviceTrustState())
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client2.getDeviceTrustState())
		assertTrue(FakeMatrixSdkState.verificationCancelCalls >= 1)

		handle1!!.confirm()
		handle2!!.confirm()
		assertEquals(0, FakeMatrixSdkState.verificationApproveCalls)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client1.getDeviceTrustState())
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client2.getDeviceTrustState())
	}

	@Test fun `mismatch decline never marks device trusted`() {
		loginBoth()
		val state1 = AtomicReference<MatrixVerificationState>()
		val state2 = AtomicReference<MatrixVerificationState>()
		val latch1Comparing = CountDownLatch(1)
		val latch1Terminal = CountDownLatch(1)
		val latch2Terminal = CountDownLatch(1)

		val handle2 = client2.startVerification({ state ->
			state2.set(state)
			if (state is MatrixVerificationState.Failed || state is MatrixVerificationState.Cancelled) {
				latch2Terminal.countDown()
			}
		}, 10_000L)
		assertNotNull(handle2)

		val handle1 = client1.startVerification({ state ->
			state1.set(state)
			if (state is MatrixVerificationState.Comparing) latch1Comparing.countDown()
			if (state is MatrixVerificationState.Failed || state is MatrixVerificationState.Cancelled) {
				latch1Terminal.countDown()
			}
		}, 10_000L)

		assertTrue(latch1Comparing.await(5, SECONDS))
		handle1!!.decline()

		assertTrue(latch1Terminal.await(5, SECONDS))
		assertTrue(latch2Terminal.await(5, SECONDS))

		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client1.getDeviceTrustState())
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client2.getDeviceTrustState())
	}

	@Test fun `cancellation never marks device trusted`() {
		loginBoth()
		val state1 = AtomicReference<MatrixVerificationState>()
		val latch1Terminal = CountDownLatch(1)

		val handle1 = client1.startVerification({ state ->
			state1.set(state)
			if (state is MatrixVerificationState.Cancelled) latch1Terminal.countDown()
		}, 10_000L)

		handle1!!.cancel()
		assertTrue(latch1Terminal.await(5, SECONDS))
		assertEquals(MatrixVerificationState.Cancelled, state1.get())
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client1.getDeviceTrustState())
	}

	@Test fun `timeout never marks device trusted`() {
		loginBoth()
		val state1 = AtomicReference<MatrixVerificationState>()
		val latchTimedOut = CountDownLatch(1)

		val handle1 = client1.startVerification({ state ->
			state1.set(state)
			if (state is MatrixVerificationState.TimedOut) latchTimedOut.countDown()
		}, 150L)

		assertNotNull(handle1)
		assertTrue(latchTimedOut.await(5, SECONDS))
		assertEquals(MatrixVerificationState.TimedOut, state1.get())
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client1.getDeviceTrustState())
	}

	@Test fun `callbacks during requestDeviceVerification not overwritten by Requesting`() {
		assertEquals(NONE, client1.login("https://matrix.example.org", "alice", "password"))
		val receivedStates = CopyOnWriteArrayList<MatrixVerificationState>()
		val latchComparing = CountDownLatch(1)

		val handle = client1.startVerification({ state ->
			receivedStates.add(state)
			if (state is MatrixVerificationState.Comparing) latchComparing.countDown()
		}, 10_000L)

		assertNotNull(handle)
		assertTrue(latchComparing.await(5, SECONDS))
		assertTrue(receivedStates.isNotEmpty())
		assertEquals(MatrixVerificationState.Requesting, receivedStates.first())
		assertTrue(receivedStates.last() is MatrixVerificationState.Comparing)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, client1.getDeviceTrustState())
		handle!!.cancel()
	}

	@Test fun `approval queued before cancellation is rejected and never marks device trusted`() {
		val controlledExecutor = ControlledExecutor()
		controlledExecutor.allowExecution = true
		val controlledClient =
			ReflectiveMatrixHomeserverDiscoveryClient(typingExecutor = controlledExecutor)
		val peerExecutor = ControlledExecutor().apply { allowExecution = true }
		val peerClient =
			ReflectiveMatrixHomeserverDiscoveryClient(typingExecutor = peerExecutor)
		try {
			assertEquals(NONE, controlledClient.login("https://matrix.example.org", "alice", "password"))
			assertEquals(NONE, peerClient.login("https://matrix.example.org", "alice", "password"))

			val state1 = AtomicReference<MatrixVerificationState>()
			val state2 = AtomicReference<MatrixVerificationState>()
			val latch1Comparing = CountDownLatch(1)
			val latch2Comparing = CountDownLatch(1)

			val handle2 = peerClient.startVerification({ state ->
				state2.set(state)
				if (state is MatrixVerificationState.Comparing) latch2Comparing.countDown()
			}, 10_000L)

			val handle1 = controlledClient.startVerification({ state ->
				state1.set(state)
				if (state is MatrixVerificationState.Comparing) latch1Comparing.countDown()
			}, 10_000L)

			assertNotNull(handle1)
			assertNotNull(handle2)
			assertTrue(latch1Comparing.await(5, SECONDS))
			assertTrue(latch2Comparing.await(5, SECONDS))

			// Peer (peerClient) approves verification synchronously via peerExecutor
			handle2!!.confirm()

			// Block the controlled executor to simulate typingExecutor being occupied
			controlledExecutor.allowExecution = false

			// client1 confirms first, queueing approveVerification on typingExecutor
			handle1!!.confirm()

			// client1 cancels while approval is still queued
			handle1.cancel()
			assertEquals(MatrixVerificationState.Cancelled, state1.get())

			// Now run all queued tasks on the occupied executor (confirm followed by cancel)
			controlledExecutor.runQueued()

			// Rechecking cancellation inside queued approval work ensures approveVerification
			// was rejected and not invoked on the controller after cancellation
			assertEquals(1, FakeMatrixSdkState.verificationApproveCalls) // only peer's confirm
			assertEquals(MatrixVerificationState.Cancelled, state1.get())
			assertEquals(MatrixDeviceTrustState.UNVERIFIED, controlledClient.getDeviceTrustState())
			assertEquals(MatrixDeviceTrustState.UNVERIFIED, peerClient.getDeviceTrustState())
		} finally {
			controlledClient.close()
			peerClient.close()
		}
	}

	@Test fun `cancellation dispatches sync service stop asynchronously on typing executor`() {
		val controlledExecutor = ControlledExecutor()
		controlledExecutor.allowExecution = true
		val controlledClient =
			ReflectiveMatrixHomeserverDiscoveryClient(typingExecutor = controlledExecutor)
		try {
			assertEquals(NONE, controlledClient.login("https://matrix.example.org", "alice", "password"))
			val handle = controlledClient.startVerification({}, 10_000L)
			assertNotNull(handle)
			assertEquals(1, FakeMatrixSdkState.syncServiceStartCalls)
			assertEquals(0, FakeMatrixSdkState.syncServiceStopCalls)

			controlledExecutor.allowExecution = false
			handle!!.cancel()

			assertEquals(0, FakeMatrixSdkState.syncServiceStopCalls)

			controlledExecutor.runQueued()
			assertEquals(1, FakeMatrixSdkState.syncServiceStopCalls)
		} finally {
			controlledClient.close()
		}
	}

	@Test fun `decline dispatches sync service stop asynchronously on typing executor`() {
		val controlledExecutor = ControlledExecutor()
		controlledExecutor.allowExecution = true
		val controlledClient =
			ReflectiveMatrixHomeserverDiscoveryClient(typingExecutor = controlledExecutor)
		try {
			assertEquals(NONE, controlledClient.login("https://matrix.example.org", "alice", "password"))
			val handle = controlledClient.startVerification({}, 10_000L)
			assertNotNull(handle)
			assertEquals(1, FakeMatrixSdkState.syncServiceStartCalls)
			assertEquals(0, FakeMatrixSdkState.syncServiceStopCalls)

			controlledExecutor.allowExecution = false
			handle!!.decline()

			assertEquals(0, FakeMatrixSdkState.syncServiceStopCalls)

			controlledExecutor.runQueued()
			assertEquals(1, FakeMatrixSdkState.syncServiceStopCalls)
		} finally {
			controlledClient.close()
		}
	}

	@Test fun `client logout cancels active verification sessions and prevents subsequent approval`() {
		val controlledExecutor = ControlledExecutor()
		controlledExecutor.allowExecution = true
		val controlledClient =
			ReflectiveMatrixHomeserverDiscoveryClient(typingExecutor = controlledExecutor)
		try {
			assertEquals(NONE, controlledClient.login("https://matrix.example.org", "alice", "password"))
			val states = CopyOnWriteArrayList<MatrixVerificationState>()
			val handle = controlledClient.startVerification({ states.add(it) }, 10_000L)
			assertNotNull(handle)
			assertEquals(1, FakeMatrixSdkState.syncServiceStartCalls)

			controlledClient.logout()

			val deadline = System.currentTimeMillis() + 5000
			while (FakeMatrixSdkState.syncServiceStopCalls < 1 && System.currentTimeMillis() < deadline) {
				Thread.sleep(10)
			}
			assertEquals(1, FakeMatrixSdkState.syncServiceStopCalls)

			handle!!.confirm()
			assertEquals(0, FakeMatrixSdkState.verificationApproveCalls)
			assertFalse(
				states.any {
					it is MatrixVerificationState.Verified ||
						it is MatrixVerificationState.Failed
				},
			)
		} finally {
			controlledClient.close()
		}
	}

	@Test fun `queued approval on typing executor is rejected after client close`() {
		val controlledExecutor = ControlledExecutor()
		controlledExecutor.allowExecution = true
		val controlledClient =
			ReflectiveMatrixHomeserverDiscoveryClient(typingExecutor = controlledExecutor)
		try {
			assertEquals(NONE, controlledClient.login("https://matrix.example.org", "alice", "password"))
			val handle = controlledClient.startVerification({}, 10_000L)
			assertNotNull(handle)

			controlledExecutor.allowExecution = false
			handle!!.confirm()

			controlledClient.close()

			controlledExecutor.runQueued()
			assertEquals(0, FakeMatrixSdkState.verificationApproveCalls)
		} finally {
			controlledClient.close()
		}
	}

	private class ControlledExecutor : Executor {
		val queue = ArrayDeque<Runnable>()
		var allowExecution = false

		override fun execute(command: Runnable) {
			synchronized(queue) {
				if (allowExecution) {
					command.run()
				} else {
					queue.add(command)
				}
			}
		}

		fun runQueued() {
			val tasks = synchronized(queue) {
				val list = ArrayList(queue)
				queue.clear()
				list
			}
			for (task in tasks) task.run()
		}
	}
}
