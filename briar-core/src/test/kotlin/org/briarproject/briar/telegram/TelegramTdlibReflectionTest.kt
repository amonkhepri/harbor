package org.briarproject.briar.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramTdlibReflectionTest {

	@Test
	fun testTelegramCodeDeliveryStatusIsSanitized() {
		val update = UpdateAuthorizationState(
			AuthorizationStateWaitCode(
				AuthenticationCodeInfo(
					phoneNumber = "+48123456789",
					type = AuthenticationCodeTypeTelegramMessage(),
					nextType = AuthenticationCodeTypeSms(),
					timeout = 42,
				),
			),
		)

		val status = getTelegramCodeDeliveryStatus(update)

		assertEquals("current=TELEGRAM_MESSAGE next=SMS timeout_seconds=42", status)
		assertFalse(status!!.contains("+48123456789"))
	}

	@Test
	fun testAwaitAuthorizationCloseReturnsCapturedState() {
		val update = PendingAuthorizationUpdate("AuthorizationStateClosed")
		val gate = TelegramTdlibAccessGate()
		update.capture("AuthorizationStateClosed")
		val state = awaitAuthorizationClose(update, gate, timeoutMs = 1_000L)
		assertEquals("AuthorizationStateClosed", state)
	}

	@Test
	fun testAwaitAuthorizationCloseReturnsNullOnTimeout() {
		val update = PendingAuthorizationUpdate("AuthorizationStateClosed")
		val gate = TelegramTdlibAccessGate()
		val state = awaitAuthorizationClose(update, gate, timeoutMs = 20L)
		assertNull(state)
	}

	@Test
	fun testAwaitAuthorizationCloseUsesCancellationGraceTimeoutWhenGateStopped() {
		val update = PendingAuthorizationUpdate("AuthorizationStateClosed")
		val gate = TelegramTdlibAccessGate()
		gate.stop {}
		val startNanos = System.nanoTime()
		val state = awaitAuthorizationClose(
			closeUpdate = update,
			accessGate = gate,
			timeoutMs = 10_000L,
			cancellationCloseTimeoutMs = 50L,
		)
		val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
		assertNull(state)
		assertTrue("Expected bounded cancellation wait, took ${elapsedMs}ms", elapsedMs < 2_000L)
	}

	@Test(expected = InterruptedException::class)
	fun testAwaitAuthorizationCloseThrowsWhenThreadInterrupted() {
		val update = PendingAuthorizationUpdate("AuthorizationStateClosed")
		val gate = TelegramTdlibAccessGate()
		Thread.currentThread().interrupt()
		try {
			awaitAuthorizationClose(update, gate, timeoutMs = 1_000L)
		} finally {
			Thread.interrupted()
		}
	}
}

private class UpdateAuthorizationState(@JvmField val authorizationState: Any)

private class AuthorizationStateWaitCode(@JvmField val codeInfo: AuthenticationCodeInfo)

private class AuthenticationCodeInfo(
	@Suppress("unused") @JvmField val phoneNumber: String,
	@JvmField val type: Any,
	@JvmField val nextType: Any?,
	@JvmField val timeout: Int,
)

private class AuthenticationCodeTypeTelegramMessage

private class AuthenticationCodeTypeSms
