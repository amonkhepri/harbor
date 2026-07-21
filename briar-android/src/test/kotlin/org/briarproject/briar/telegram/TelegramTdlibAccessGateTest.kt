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
}
