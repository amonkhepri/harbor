package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthState
import org.drinkless.tdlib.Client
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReflectiveTelegramTdlibLoginClientTest {

	@After
	fun tearDown() {
		Client.resetTestState()
	}

	@Test
	fun testStartThenSubmitIdentifierTransitionsToCodeEntry() {
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())

		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf("SetTdlibParameters", "SetAuthenticationPhoneNumber"),
			Client.getSentRequestNames(),
		)
		assertEquals("+123456789", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitIdentifierUsesConfiguredWritableTdlibDirectories() {
		val tdlibDir = File("build/test-tdlib-dir")
		val client = ReflectiveTelegramTdlibLoginClient(tdlibDir)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			File(tdlibDir, "database").path,
			Client.getLastDatabaseDirectory(),
		)
		assertEquals(File(tdlibDir, "files").path, Client.getLastFilesDirectory())

		client.close()
	}

	@Test
	fun testStartWaitsForBriefDelayedAuthorizationUpdate() {
		Client.setAuthorizationUpdateDelayMs(300L)
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())

		client.close()
	}

	@Test
	fun testStartReturnsRecoverableErrorWhenInitialAuthorizationUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelayMs(1_200L)
		val client = ReflectiveTelegramTdlibLoginClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		val startTime = System.currentTimeMillis()
		assertEquals(TelegramAuthState.RECOVERABLE_ERROR, client.start())
		val elapsed = System.currentTimeMillis() - startTime
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertTrue(
			"Expected start wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertEquals(listOf("Close"), Client.getSentRequestNames())
		assertEquals("", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitIdentifierWaitsForBriefDelayedAuthorizationUpdate() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 300L)
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())

		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf("SetTdlibParameters", "SetAuthenticationPhoneNumber"),
			Client.getSentRequestNames(),
		)
		assertEquals("+123456789", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitIdentifierAllowsDelayedPhoneNumberResult() {
		Client.setPhoneNumberResultDelayMs(1_200L)
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		val startTime = System.currentTimeMillis()
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue("Expected delayed phone result, got ${elapsed}ms", elapsed >= 1_100L)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf("SetTdlibParameters", "SetAuthenticationPhoneNumber"),
			Client.getSentRequestNames(),
		)
		assertEquals("+123456789", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitIdentifierReturnsRecoverableErrorWhenCodeUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 1_200L)
		val client = ReflectiveTelegramTdlibLoginClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		val startTime = System.currentTimeMillis()
		assertEquals(
			TelegramAuthState.RECOVERABLE_ERROR,
			client.submitIdentifier("+123456789"),
		)
		val elapsed = System.currentTimeMillis() - startTime
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertTrue(
			"Expected identifier wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertEquals(
			listOf("SetTdlibParameters", "SetAuthenticationPhoneNumber", "Close"),
			Client.getSentRequestNames(),
		)
		assertEquals("+123456789", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitInvalidIdentifierReturnsRecoverableError() {
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.RECOVERABLE_ERROR,
			client.submitIdentifier("invalid-phone"),
		)
		assertEquals(
			RecoverableErrorDetail.INVALID_IDENTIFIER,
			client.getRecoverableErrorDetail(),
		)
		assertEquals(
			listOf("SetTdlibParameters", "SetAuthenticationPhoneNumber"),
			Client.getSentRequestNames(),
		)
		assertEquals("invalid-phone", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitInvalidCodeReturnsRecoverableError() {
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.RECOVERABLE_ERROR,
			client.submitCode("invalid-code"),
		)
		assertEquals(
			RecoverableErrorDetail.INVALID_CODE,
			client.getRecoverableErrorDetail(),
		)
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitCodeTransitionsToReady() {
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(TelegramAuthState.READY, client.submitCode("12345"))
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitCodeWaitsForBriefDelayedReadyAuthorizationUpdate() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 300L)
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(TelegramAuthState.READY, client.submitCode("12345"))
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitCodeReturnsRecoverableErrorWhenReadyUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 1_200L)
		val client = ReflectiveTelegramTdlibLoginClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		val startTime = System.currentTimeMillis()
		assertEquals(
			TelegramAuthState.RECOVERABLE_ERROR,
			client.submitCode("12345"),
		)
		val elapsed = System.currentTimeMillis() - startTime
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertTrue(
			"Expected code wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"Close",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitCodeWaitsForBriefDelayedPasswordAuthorizationUpdate() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 300L)
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitCodeReturnsRecoverableErrorWhenPasswordUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 1_200L)
		val client = ReflectiveTelegramTdlibLoginClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		val startTime = System.currentTimeMillis()
		assertEquals(
			TelegramAuthState.RECOVERABLE_ERROR,
			client.submitCode("password-required"),
		)
		val elapsed = System.currentTimeMillis() - startTime
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertTrue(
			"Expected password-entry wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"Close",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitCodeTransitionsToPasswordEntry() {
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitPasswordTransitionsToReady() {
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		assertEquals(TelegramAuthState.READY, client.submitPassword("hunter2"))
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"CheckAuthenticationPassword",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testStartIgnoresDelayedReadyUpdateFromClosedPasswordSession() {
		val client = ReflectiveTelegramTdlibLoginClient()
		val delayedPasswordResult = arrayOfNulls<TelegramAuthState>(1)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)

		Client.setAuthorizationUpdateDelaySequenceMs(300L, 0L, 400L)
		val submitPasswordThread = Thread {
			delayedPasswordResult[0] = client.submitPassword("hunter2")
		}
		submitPasswordThread.start()

		Thread.sleep(50L)

		assertEquals(TelegramAuthState.CLOSED, client.close())
		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())

		submitPasswordThread.join()
		assertEquals(TelegramAuthState.CLOSED, delayedPasswordResult[0])
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"CheckAuthenticationPassword",
				"Close",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitInvalidPasswordReturnsRecoverableErrorAndAllowsRetry() {
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		assertEquals(
			TelegramAuthState.RECOVERABLE_ERROR,
			client.submitPassword("invalid-password"),
		)
		assertEquals(
			RecoverableErrorDetail.INVALID_PASSWORD,
			client.getRecoverableErrorDetail(),
		)
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"CheckAuthenticationPassword",
			),
			Client.getSentRequestNames(),
		)

		assertEquals(TelegramAuthState.READY, client.submitPassword("hunter2"))
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"CheckAuthenticationPassword",
				"CheckAuthenticationPassword",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitPasswordWaitsForDelayedReadyAuthorizationUpdate() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 0L, 500L)
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		val startTime = System.currentTimeMillis()
		assertEquals(TelegramAuthState.READY, client.submitPassword("hunter2"))
		val elapsed = System.currentTimeMillis() - startTime
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertTrue("Expected delayed password update, got ${elapsed}ms", elapsed >= 400L)
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"CheckAuthenticationPassword",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testSubmitPasswordReturnsRecoverableErrorWhenReadyUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 0L, 1_200L)
		val client = ReflectiveTelegramTdlibLoginClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		val startTime = System.currentTimeMillis()
		assertEquals(
			TelegramAuthState.RECOVERABLE_ERROR,
			client.submitPassword("hunter2"),
		)
		val elapsed = System.currentTimeMillis() - startTime
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertTrue(
			"Expected password wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"CheckAuthenticationPassword",
				"Close",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}

	@Test
	fun testCloseAfterInvalidPasswordClearsRecoverableErrorAndAllowsRestart() {
		val client = ReflectiveTelegramTdlibLoginClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		assertEquals(
			TelegramAuthState.RECOVERABLE_ERROR,
			client.submitPassword("invalid-password"),
		)
		assertEquals(
			RecoverableErrorDetail.INVALID_PASSWORD,
			client.getRecoverableErrorDetail(),
		)

		assertEquals(TelegramAuthState.CLOSED, client.close())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"CheckAuthenticationPassword",
				"Close",
			),
			Client.getSentRequestNames(),
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("+123456789"),
		)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			listOf(
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
				"CheckAuthenticationCode",
				"CheckAuthenticationPassword",
				"Close",
				"SetTdlibParameters",
				"SetAuthenticationPhoneNumber",
			),
			Client.getSentRequestNames(),
		)

		client.close()
	}
}
