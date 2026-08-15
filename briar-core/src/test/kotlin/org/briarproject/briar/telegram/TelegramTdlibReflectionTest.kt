package org.briarproject.briar.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
