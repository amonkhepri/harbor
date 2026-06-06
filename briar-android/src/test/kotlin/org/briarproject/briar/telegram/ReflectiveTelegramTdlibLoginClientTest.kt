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

	private fun createClient(
		tdlibDirectory: File = File("harbor-telegram"),
		authorizationUpdateTimeoutMs: Long = 10_000L,
	) = ReflectiveTelegramTdlibLoginClient(
		tdlibDirectory = tdlibDirectory,
		apiId = 12345,
		apiHash = "test-api-hash",
		authorizationUpdateTimeoutMs = authorizationUpdateTimeoutMs,
	)

	private fun startToCodeEntry(
		client: ReflectiveTelegramTdlibLoginClient,
		identifier: String = "test-login-identifier",
	) {
		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(TelegramAuthState.CODE_ENTRY, client.submitIdentifier(identifier))
	}

	private fun startToPasswordEntry(client: ReflectiveTelegramTdlibLoginClient) {
		startToCodeEntry(client)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
	}

	private fun assertSentRequests(vararg requestNames: String) {
		assertEquals(requestNames.toList(), Client.getSentRequestNames())
	}

	private fun assertRecoverableError(
		client: ReflectiveTelegramTdlibLoginClient,
		expectedDetail: RecoverableErrorDetail,
		action: () -> TelegramAuthState,
	) {
		assertEquals(TelegramAuthState.RECOVERABLE_ERROR, action())
		assertEquals(expectedDetail, client.getRecoverableErrorDetail())
	}

	private companion object {
		private const val SET_PARAMETERS = "SetTdlibParameters"
		private const val SET_PHONE = "SetAuthenticationPhoneNumber"
		private const val CHECK_CODE = "CheckAuthenticationCode"
		private const val CHECK_PASSWORD = "CheckAuthenticationPassword"
		private const val CLOSE = "Close"
	}

	@Test
	fun testStartThenSubmitIdentifierTransitionsToCodeEntry() {
		val client = createClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())

		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("test-login-identifier"),
		)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertSentRequests(SET_PARAMETERS, SET_PHONE)
		assertEquals("test-login-identifier", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitIdentifierUsesConfiguredWritableTdlibDirectories() {
		val tdlibDir = File("build/test-tdlib-dir")
		val client = createClient(tdlibDirectory = tdlibDir)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("test-login-identifier"),
		)
		assertEquals(
			File(tdlibDir, "database").path,
			Client.getLastDatabaseDirectory(),
		)
		assertEquals(File(tdlibDir, "files").path, Client.getLastFilesDirectory())

		client.close()
	}

	@Test
	fun testSubmitIdentifierUsesConfiguredApiCredentials() {
		val client = ReflectiveTelegramTdlibLoginClient(
			apiId = 12345,
			apiHash = "test-api-hash",
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("test-login-identifier"),
		)
		assertEquals(12345, Client.getLastApiId())
		assertEquals("test-api-hash", Client.getLastApiHash())

		client.close()
	}

	@Test
	fun testSubmitIdentifierReturnsMissingCredentialsWithoutConfiguredApiId() {
		val client = ReflectiveTelegramTdlibLoginClient(
			apiId = 0,
			apiHash = "test-api-hash",
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertRecoverableError(
			client,
			RecoverableErrorDetail.MISSING_API_CREDENTIALS,
		) { client.submitIdentifier("test-login-identifier") }
		assertSentRequests()

		client.close()
	}

	@Test
	fun testSubmitIdentifierReturnsMissingCredentialsWithoutConfiguredApiHash() {
		val client = ReflectiveTelegramTdlibLoginClient(
			apiId = 12345,
			apiHash = "",
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertRecoverableError(
			client,
			RecoverableErrorDetail.MISSING_API_CREDENTIALS,
		) { client.submitIdentifier("test-login-identifier") }
		assertSentRequests()

		client.close()
	}

	@Test
	fun testSubmitIdentifierReturnsMissingCredentialsWhenTdlibRejectsParameters() {
		Client.setTdlibParametersError(true)
		val client = ReflectiveTelegramTdlibLoginClient(
			apiId = 12345,
			apiHash = "test-api-hash",
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertRecoverableError(
			client,
			RecoverableErrorDetail.MISSING_API_CREDENTIALS,
		) { client.submitIdentifier("test-login-identifier") }
		assertSentRequests(SET_PARAMETERS)

		client.close()
	}

	@Test
	fun testStartWaitsForBriefDelayedAuthorizationUpdate() {
		Client.setAuthorizationUpdateDelayMs(300L)
		val client = createClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())

		client.close()
	}

	@Test
	fun testStartReturnsRecoverableErrorWhenInitialAuthorizationUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelayMs(1_200L)
		val client = createClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		val startTime = System.currentTimeMillis()
		assertRecoverableError(client, RecoverableErrorDetail.NONE) { client.start() }
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue(
			"Expected start wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertSentRequests(CLOSE)
		assertEquals("", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitIdentifierAllowsDelayedPhoneNumberResult() {
		Client.setPhoneNumberResultDelayMs(1_200L)
		val client = createClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		val startTime = System.currentTimeMillis()
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("test-login-identifier"),
		)
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue("Expected delayed phone result, got ${elapsed}ms", elapsed >= 1_100L)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertSentRequests(SET_PARAMETERS, SET_PHONE)
		assertEquals("test-login-identifier", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitIdentifierReturnsRecoverableErrorWhenCodeUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 1_200L)
		val client = createClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		val startTime = System.currentTimeMillis()
		assertRecoverableError(
			client,
			RecoverableErrorDetail.NONE,
		) { client.submitIdentifier("test-login-identifier") }
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue(
			"Expected identifier wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CLOSE)
		assertEquals("test-login-identifier", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitInvalidIdentifierReturnsRecoverableError() {
		val client = createClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertRecoverableError(
			client,
			RecoverableErrorDetail.INVALID_IDENTIFIER,
		) { client.submitIdentifier("invalid-phone") }
		assertSentRequests(SET_PARAMETERS, SET_PHONE)
		assertEquals("invalid-phone", Client.getLastPhoneNumber())

		client.close()
	}

	@Test
	fun testSubmitInvalidCodeReturnsRecoverableError() {
		val client = createClient()

		startToCodeEntry(client)
		assertRecoverableError(
			client,
			RecoverableErrorDetail.INVALID_CODE,
		) { client.submitCode("invalid-code") }
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE)

		client.close()
	}

	@Test
	fun testSubmitCodeTransitionsToReady() {
		val client = createClient()

		startToCodeEntry(client)
		assertEquals(TelegramAuthState.READY, client.submitCode("12345"))
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE)

		client.close()
	}

	@Test
	fun testSubmitCodeReturnsRecoverableErrorWhenReadyUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 1_200L)
		val client = createClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		startToCodeEntry(client)
		val startTime = System.currentTimeMillis()
		assertRecoverableError(
			client,
			RecoverableErrorDetail.NONE,
		) { client.submitCode("12345") }
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue(
			"Expected code wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CLOSE)

		client.close()
	}

	@Test
	fun testSubmitCodeReturnsRecoverableErrorWhenPasswordUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 1_200L)
		val client = createClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		startToCodeEntry(client)
		val startTime = System.currentTimeMillis()
		assertRecoverableError(
			client,
			RecoverableErrorDetail.NONE,
		) { client.submitCode("password-required") }
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue(
			"Expected password-entry wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CLOSE)

		client.close()
	}

	@Test
	fun testSubmitCodeTransitionsToPasswordEntry() {
		val client = createClient()

		startToCodeEntry(client)
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE)

		client.close()
	}

	@Test
	fun testSubmitPasswordTransitionsToReady() {
		val client = createClient()

		startToPasswordEntry(client)
		assertEquals(TelegramAuthState.READY, client.submitPassword("hunter2"))
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD)

		client.close()
	}

	@Test
	fun testStartIgnoresDelayedReadyUpdateFromClosedPasswordSession() {
		val client = createClient()
		val delayedPasswordResult = arrayOfNulls<TelegramAuthState>(1)

		startToPasswordEntry(client)

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
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD, CLOSE)

		client.close()
	}

	@Test
	fun testSubmitInvalidPasswordReturnsRecoverableErrorAndAllowsRetry() {
		val client = createClient()

		startToPasswordEntry(client)
		assertRecoverableError(
			client,
			RecoverableErrorDetail.INVALID_PASSWORD,
		) { client.submitPassword("invalid-password") }
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD)

		assertEquals(TelegramAuthState.READY, client.submitPassword("hunter2"))
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD, CHECK_PASSWORD)

		client.close()
	}

	@Test
	fun testSubmitPasswordReturnsRecoverableErrorWhenReadyUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 0L, 1_200L)
		val client = createClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		startToPasswordEntry(client)
		val startTime = System.currentTimeMillis()
		assertRecoverableError(
			client,
			RecoverableErrorDetail.NONE,
		) { client.submitPassword("hunter2") }
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue(
			"Expected password wait timeout around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD, CLOSE)

		client.close()
	}

	@Test
	fun testCloseAfterInvalidPasswordClearsRecoverableErrorAndAllowsRestart() {
		val client = createClient()

		startToPasswordEntry(client)
		assertRecoverableError(
			client,
			RecoverableErrorDetail.INVALID_PASSWORD,
		) { client.submitPassword("invalid-password") }

		assertEquals(TelegramAuthState.CLOSED, client.close())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD, CLOSE)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("test-login-identifier"),
		)
		assertEquals(RecoverableErrorDetail.NONE, client.getRecoverableErrorDetail())
		assertSentRequests(
			SET_PARAMETERS,
			SET_PHONE,
			CHECK_CODE,
			CHECK_PASSWORD,
			CLOSE,
			SET_PARAMETERS,
			SET_PHONE,
		)

		client.close()
	}
}
