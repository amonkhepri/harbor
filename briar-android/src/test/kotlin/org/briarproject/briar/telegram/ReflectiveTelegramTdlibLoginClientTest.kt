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

	private fun assertSentRequestsAndClose(
		client: ReflectiveTelegramTdlibLoginClient,
		vararg requestNames: String,
	) {
		assertSentRequests(*requestNames)
		client.close()
	}

	private fun assertPhoneRequestsAndClose(
		client: ReflectiveTelegramTdlibLoginClient,
		vararg requestNames: String,
		expectedPhone: String = "test-login-identifier",
	) {
		assertSentRequests(*requestNames)
		assertEquals(expectedPhone, Client.getLastPhoneNumber())
		client.close()
	}

	private fun ReflectiveTelegramTdlibLoginClient.assertSuccessfulState(
		expectedState: TelegramAuthState,
		actualState: TelegramAuthState,
	) {
		assertEquals(expectedState, actualState)
		assertEquals(RecoverableErrorDetail.NONE, getRecoverableErrorDetail())
	}

	private fun assertRecoverableError(
		client: ReflectiveTelegramTdlibLoginClient,
		expectedDetail: RecoverableErrorDetail,
		action: () -> TelegramAuthState,
	) {
		assertEquals(TelegramAuthState.RECOVERABLE_ERROR, action())
		assertEquals(expectedDetail, client.getRecoverableErrorDetail())
	}

	private fun assertRecoverableTimeout(
		client: ReflectiveTelegramTdlibLoginClient,
		expectedWaitDescription: String,
		action: () -> TelegramAuthState,
	) {
		val startTime = System.currentTimeMillis()
		assertRecoverableError(client, RecoverableErrorDetail.NONE, action)
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue(
			"Expected $expectedWaitDescription around 1s, got ${elapsed}ms",
			elapsed >= 900L,
		)
	}

	private fun assertMissingCredentialsAfterIdentifierSubmit(
		client: ReflectiveTelegramTdlibLoginClient,
		vararg requestNames: String,
	) {
		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertRecoverableError(
			client,
			RecoverableErrorDetail.MISSING_API_CREDENTIALS,
		) { client.submitIdentifier("test-login-identifier") }
		assertSentRequestsAndClose(client, *requestNames)
	}

	private fun assertSubmitCodeTimeout(
		submittedCode: String,
		expectedWaitDescription: String,
	) {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 0L, 1_200L)
		val client = createClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		startToCodeEntry(client)
		assertRecoverableTimeout(client, expectedWaitDescription) {
			client.submitCode(submittedCode)
		}
		assertSentRequestsAndClose(client, SET_PARAMETERS, SET_PHONE, CHECK_CODE, CLOSE)
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

		client.assertSuccessfulState(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		client.assertSuccessfulState(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("test-login-identifier"),
		)
		assertPhoneRequestsAndClose(client, SET_PARAMETERS, SET_PHONE)
	}

	@Test
	fun testSubmitIdentifierUsesConfiguredWritableTdlibDirectories() {
		val tdlibDir = File("build/test-tdlib-dir")
		val client = createClient(tdlibDirectory = tdlibDir)

		startToCodeEntry(client)
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

		startToCodeEntry(client)
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

		assertMissingCredentialsAfterIdentifierSubmit(client)
	}

	@Test
	fun testSubmitIdentifierReturnsMissingCredentialsWithoutConfiguredApiHash() {
		val client = ReflectiveTelegramTdlibLoginClient(
			apiId = 12345,
			apiHash = "",
		)

		assertMissingCredentialsAfterIdentifierSubmit(client)
	}

	@Test
	fun testSubmitIdentifierReturnsMissingCredentialsWhenTdlibRejectsParameters() {
		Client.setTdlibParametersError(true)
		val client = ReflectiveTelegramTdlibLoginClient(
			apiId = 12345,
			apiHash = "test-api-hash",
		)

		assertMissingCredentialsAfterIdentifierSubmit(client, SET_PARAMETERS)
	}

	@Test
	fun testStartWaitsForBriefDelayedAuthorizationUpdate() {
		Client.setAuthorizationUpdateDelayMs(300L)
		val client = createClient()

		client.assertSuccessfulState(TelegramAuthState.IDENTIFIER_ENTRY, client.start())

		client.close()
	}

	@Test
	fun testStartReturnsRecoverableErrorWhenInitialAuthorizationUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelayMs(1_200L)
		val client = createClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		assertRecoverableTimeout(client, "start wait timeout") { client.start() }
		assertPhoneRequestsAndClose(client, CLOSE, expectedPhone = "")
	}

	@Test
	fun testSubmitIdentifierAllowsDelayedPhoneNumberResult() {
		Client.setPhoneNumberResultDelayMs(1_200L)
		val client = createClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		val startTime = System.currentTimeMillis()
		client.assertSuccessfulState(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("test-login-identifier"),
		)
		val elapsed = System.currentTimeMillis() - startTime
		assertTrue("Expected delayed phone result, got ${elapsed}ms", elapsed >= 1_100L)
		assertPhoneRequestsAndClose(client, SET_PARAMETERS, SET_PHONE)
	}

	@Test
	fun testSubmitIdentifierReturnsRecoverableErrorWhenCodeUpdateExceedsTimeout() {
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 0L, 1_200L)
		val client = createClient(
			authorizationUpdateTimeoutMs = 1_000L,
		)

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertRecoverableTimeout(client, "identifier wait timeout") {
			client.submitIdentifier("test-login-identifier")
		}
		assertPhoneRequestsAndClose(client, SET_PARAMETERS, SET_PHONE, CLOSE)
	}

	@Test
	fun testSubmitInvalidIdentifierReturnsRecoverableError() {
		val client = createClient()

		assertEquals(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		assertRecoverableError(
			client,
			RecoverableErrorDetail.INVALID_IDENTIFIER,
		) { client.submitIdentifier("invalid-phone") }
		assertPhoneRequestsAndClose(client, SET_PARAMETERS, SET_PHONE, expectedPhone = "invalid-phone")
	}

	@Test
	fun testSubmitInvalidCodeReturnsRecoverableError() {
		val client = createClient()

		startToCodeEntry(client)
		assertRecoverableError(
			client,
			RecoverableErrorDetail.INVALID_CODE,
		) { client.submitCode("invalid-code") }
		assertSentRequestsAndClose(client, SET_PARAMETERS, SET_PHONE, CHECK_CODE)
	}

	@Test
	fun testSubmitCodeTransitionsToReady() {
		val client = createClient()

		startToCodeEntry(client)
		client.assertSuccessfulState(TelegramAuthState.READY, client.submitCode("12345"))
		assertSentRequestsAndClose(client, SET_PARAMETERS, SET_PHONE, CHECK_CODE)
	}

	@Test
	fun testSubmitCodeReturnsRecoverableErrorWhenReadyUpdateExceedsTimeout() {
		assertSubmitCodeTimeout("12345", "code wait timeout")
	}

	@Test
	fun testSubmitCodeReturnsRecoverableErrorWhenPasswordUpdateExceedsTimeout() {
		assertSubmitCodeTimeout("password-required", "password-entry wait timeout")
	}

	@Test
	fun testSubmitCodeTransitionsToPasswordEntry() {
		val client = createClient()

		startToCodeEntry(client)
		client.assertSuccessfulState(
			TelegramAuthState.PASSWORD_ENTRY,
			client.submitCode("password-required"),
		)
		assertSentRequestsAndClose(client, SET_PARAMETERS, SET_PHONE, CHECK_CODE)
	}

	@Test
	fun testSubmitPasswordTransitionsToReady() {
		val client = createClient()

		startToPasswordEntry(client)
		client.assertSuccessfulState(TelegramAuthState.READY, client.submitPassword("hunter2"))
		assertSentRequestsAndClose(client, SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD)
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
		client.assertSuccessfulState(TelegramAuthState.IDENTIFIER_ENTRY, client.start())

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

		client.assertSuccessfulState(TelegramAuthState.READY, client.submitPassword("hunter2"))
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
		assertRecoverableTimeout(client, "password wait timeout") {
			client.submitPassword("hunter2")
		}
		assertSentRequestsAndClose(client, SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD, CLOSE)
	}

	@Test
	fun testCloseAfterInvalidPasswordClearsRecoverableErrorAndAllowsRestart() {
		val client = createClient()

		startToPasswordEntry(client)
		assertRecoverableError(
			client,
			RecoverableErrorDetail.INVALID_PASSWORD,
		) { client.submitPassword("invalid-password") }

		client.assertSuccessfulState(TelegramAuthState.CLOSED, client.close())
		assertSentRequests(SET_PARAMETERS, SET_PHONE, CHECK_CODE, CHECK_PASSWORD, CLOSE)

		client.assertSuccessfulState(TelegramAuthState.IDENTIFIER_ENTRY, client.start())
		client.assertSuccessfulState(
			TelegramAuthState.CODE_ENTRY,
			client.submitIdentifier("test-login-identifier"),
		)
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
