package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthState
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramAuthSessionImplTest {

	@Test
	fun testDisabledLoginClientKeepsSessionClosed() {
		val session = TelegramAuthSessionImpl(DisabledTelegramTdlibLoginClient())

		assertDisabledSessionClosed(session)
		session.start()
		assertDisabledSessionClosed(session)
		session.submitIdentifier("test-login-identifier")
		assertDisabledSessionClosed(session)
		session.resendCode()
		assertDisabledSessionClosed(session)
		session.submitCode("12345")
		assertDisabledSessionClosed(session)
		session.submitPassword("test-password")
		assertDisabledSessionClosed(session)
		session.close()
		assertDisabledSessionClosed(session)
	}

	private fun assertDisabledSessionClosed(session: TelegramAuthSessionImpl) {
		assertEquals(
			TelegramAuthState.CLOSED to RecoverableErrorDetail.NONE,
			session.getCurrentState() to session.getRecoverableErrorDetail(),
		)
	}
}
