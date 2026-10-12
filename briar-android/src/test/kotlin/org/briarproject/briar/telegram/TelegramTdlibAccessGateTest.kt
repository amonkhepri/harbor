package org.briarproject.briar.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class TelegramTdlibAccessGateTest {

	@Test
	fun stopWaitsForActiveCallAndRejectsLaterCalls() {
		val gate = TelegramTdlibAccessGate()
		val callStarted = CountDownLatch(1)
		val releaseCall = CountDownLatch(1)
		val stopFinished = CountDownLatch(1)
		Thread {
			gate.run(Unit) {
				callStarted.countDown()
				releaseCall.await()
			}
		}.start()
		assertTrue(callStarted.await(1, SECONDS))

		Thread {
			gate.stop {}
			stopFinished.countDown()
		}.start()
		assertFalse(stopFinished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
		releaseCall.countDown()
		assertTrue(stopFinished.await(1, SECONDS))
		assertEquals("fallback", gate.run("fallback") { "called" })
	}

	@Test
	fun sleepUnlessStoppedWakesImmediatelyWhenStopped() {
		val gate = TelegramTdlibAccessGate()
		val sleepFinished = CountDownLatch(1)
		Thread {
			gate.sleepUnlessStopped(5_000L)
			sleepFinished.countDown()
		}.start()

		assertFalse(sleepFinished.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
		gate.stop {}
		assertTrue(sleepFinished.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
		assertTrue(gate.isStopped)
		assertFalse(gate.isAcceptingCalls())
	}

	@Test
	fun sleepUnlessStoppedPropagatesThreadInterruptionPromptly() {
		val gate = TelegramTdlibAccessGate()
		val aboutToSleep = CountDownLatch(1)
		val interrupted = CountDownLatch(1)
		val sleepThread = Thread {
			aboutToSleep.countDown()
			try {
				gate.sleepUnlessStopped(5_000L)
			} catch (_: InterruptedException) {
				interrupted.countDown()
			}
		}
		sleepThread.start()

		assertTrue(aboutToSleep.await(1, SECONDS))
		sleepThread.interrupt()
		assertTrue(interrupted.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
	}

	@Test
	fun awaitUnlessStoppedWakesImmediatelyWhenStopped() {
		val gate = TelegramTdlibAccessGate()
		val targetLatch = CountDownLatch(1)
		val waitFinished = CountDownLatch(1)
		var completed = true
		Thread {
			completed = gate.awaitUnlessStopped(targetLatch, 5_000L)
			waitFinished.countDown()
		}.start()

		assertFalse(waitFinished.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
		gate.stop {}
		assertTrue(waitFinished.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
		assertFalse(completed)
	}

	@Test
	fun awaitUnlessStoppedReturnsTrueWhenLatchCountedDown() {
		val gate = TelegramTdlibAccessGate()
		val targetLatch = CountDownLatch(1)
		targetLatch.countDown()
		assertTrue(gate.awaitUnlessStopped(targetLatch, 1_000L))
	}

	@Test
	fun awaitUnlessStoppedReturnsFalseWhenAlreadyStopped() {
		val gate = TelegramTdlibAccessGate()
		gate.stop {}
		val targetLatch = CountDownLatch(1)
		assertFalse(gate.awaitUnlessStopped(targetLatch, 1_000L))
	}

	@Test
	fun awaitUnlessStoppedPropagatesThreadInterruptionPromptly() {
		val gate = TelegramTdlibAccessGate()
		val targetLatch = CountDownLatch(1)
		val aboutToWait = CountDownLatch(1)
		val interrupted = CountDownLatch(1)
		val waitThread = Thread {
			aboutToWait.countDown()
			try {
				gate.awaitUnlessStopped(targetLatch, 5_000L)
			} catch (_: InterruptedException) {
				interrupted.countDown()
			}
		}
		waitThread.start()

		assertTrue(aboutToWait.await(1, SECONDS))
		waitThread.interrupt()
		assertTrue(interrupted.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
		waitThread.join(2_000L)
	}
}
