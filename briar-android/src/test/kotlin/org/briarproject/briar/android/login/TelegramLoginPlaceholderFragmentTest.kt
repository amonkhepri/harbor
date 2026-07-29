package org.briarproject.briar.android.login

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.briarproject.bramble.api.FeatureFlags
import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.event.EventBus
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState
import org.briarproject.briar.R
import org.briarproject.briar.android.login.StartupViewModel.State.SIGNED_OUT
import org.briarproject.briar.android.viewmodel.LiveDataTestUtil.getOrAwaitValue
import org.briarproject.briar.api.android.AndroidNotificationManager
import org.briarproject.briar.api.telegram.TelegramAuthSession
import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class TelegramLoginPlaceholderFragmentTest {

	@get:Rule
	val testRule = InstantTaskExecutorRule()

	@get:Rule
	val composeRule = createComposeRule()

	private lateinit var viewModel: StartupViewModel
	private lateinit var telegramAuthSession: FakeTelegramAuthSession

	@Before
	fun setUp() {
		telegramAuthSession = FakeTelegramAuthSession()
		viewModel = createViewModel(telegramAuthSession)
		viewModel.showTelegramLoginPlaceholder()
		composeRule.setContent {
			MaterialTheme {
				TelegramLoginScreen(viewModel)
			}
		}
		composeRule.waitForIdle()
	}

	@Test
	fun testIdentifierContinueShowsCodeStepAndFallbackSignsOut() {
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_STEP_TAG)
			.assertIsDisplayed()
		composeRule.onAllNodesWithTag(TELEGRAM_LOGIN_CODE_STEP_TAG)
			.assertCountEquals(0)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsNotEnabled()

		telegramAuthSession.stateAfterSubmitIdentifier =
			TelegramAuthState.CODE_ENTRY
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_TAG)
			.performTextInput(" +123456789 ")
		composeRule.waitForIdle()

		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsEnabled()
			.performClick()
		composeRule.waitForIdle()

		assertEquals("+123456789", telegramAuthSession.lastIdentifier)
		composeRule.onAllNodesWithTag(TELEGRAM_LOGIN_IDENTIFIER_STEP_TAG)
			.assertCountEquals(0)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_STEP_TAG)
			.assertIsDisplayed()
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).authState,
		)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_RESEND_TAG).performClick()
		composeRule.waitForIdle()
		assertEquals(1, telegramAuthSession.resendCodeCalls)

		composeRule.onNodeWithTag(TELEGRAM_LOGIN_BACK_TAG).performScrollTo().performClick()
		composeRule.waitForIdle()

		assertEquals(1, telegramAuthSession.closeCalls)
		assertEquals(
			TelegramAuthState.CLOSED,
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).authState,
		)
		assertEquals(SIGNED_OUT, getOrAwaitValue(viewModel.getState()))
	}

	@Test
	fun testQrActionUsesQrReadyRoute() {
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_TAG)
			.performTextInput("+123456789")
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_QR_TAG).performClick()
		composeRule.waitForIdle()

		assertEquals(1, telegramAuthSession.qrRequestCalls)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONFIRMATION_CONTINUE_TAG).performClick()
		assertEquals(SIGNED_OUT, getOrAwaitValue(viewModel.state))
		assertEquals(1, telegramAuthSession.closeCalls)
	}

	@Test
	fun testRejectedCodeResendKeepsCodeStepVisible() {
		telegramAuthSession.stateAfterSubmitIdentifier = TelegramAuthState.CODE_ENTRY
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_TAG)
			.performTextInput("+123456789")
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG).performClick()
		composeRule.waitForIdle()

		telegramAuthSession.stateAfterResend = TelegramAuthState.RECOVERABLE_ERROR
		telegramAuthSession.detailAfterResend = RecoverableErrorDetail.CODE_RESEND_FAILED
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_RESEND_TAG).performClick()
		composeRule.waitForIdle()

		assertLoginMessage(R.string.telegram_connector_login_code_resend_failed_message)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_STEP_TAG).assertIsDisplayed()
		composeRule.onAllNodesWithTag(TELEGRAM_LOGIN_IDENTIFIER_STEP_TAG).assertCountEquals(0)
	}

	@Test
	fun testConfirmationBackClearsStaleCodeFieldBeforeReopen() {
		telegramAuthSession.stateAfterSubmitIdentifier =
			TelegramAuthState.CODE_ENTRY
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_TAG)
			.performTextInput("+123456789")
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.performClick()
		composeRule.waitForIdle()

		telegramAuthSession.stateAfterSubmitCode = TelegramAuthState.READY
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_TAG)
			.performTextInput("12345")
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_CONTINUE_TAG)
			.performClick()
		composeRule.waitForIdle()

		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONFIRMATION_BACK_TAG)
			.performClick()
		composeRule.waitForIdle()

		telegramAuthSession.stateAfterSubmitIdentifier =
			TelegramAuthState.CODE_ENTRY
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.performClick()
		composeRule.waitForIdle()

		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_TAG)
			.assertEditableTextEquals("")
	}

	@Test
	fun testRepeatedRecoverableErrorDetailChangeUpdatesCodeStep() {
		telegramAuthSession.stateAfterSubmitIdentifier =
			TelegramAuthState.CODE_ENTRY
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_TAG)
			.performTextInput("+123456789")
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.performClick()
		composeRule.waitForIdle()

		telegramAuthSession.stateAfterSubmitCode =
			TelegramAuthState.RECOVERABLE_ERROR
		telegramAuthSession.detailAfterSubmitCode =
			RecoverableErrorDetail.INVALID_CODE
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_TAG)
			.performTextInput("11111")
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_CONTINUE_TAG)
			.performClick()
		composeRule.waitForIdle()

		assertLoginMessage(R.string.telegram_connector_login_code_invalid_message)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_TAG)
			.assertEditableTextEquals("11111")

		telegramAuthSession.detailAfterSubmitCode = RecoverableErrorDetail.NONE
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CODE_CONTINUE_TAG)
			.performClick()
		composeRule.waitForIdle()

		assertLoginMessage(R.string.telegram_connector_login_retry_message)
		composeRule.onAllNodesWithTag(TELEGRAM_LOGIN_CODE_STEP_TAG)
			.assertCountEquals(0)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_STEP_TAG)
			.assertIsDisplayed()
		assertEquals("", viewModel.getTelegramLoginCode())
	}

	@Test
	fun testUnsupportedAuthStepShowsSpecificMessageAndAllowsRetry() {
		assertIdentifierRecoverableErrorMessage(
			RecoverableErrorDetail.UNSUPPORTED_AUTH_STEP,
			R.string.telegram_connector_login_unsupported_auth_step_message,
		)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsEnabled()
			.performClick()
		assertEquals(2, telegramAuthSession.identifierSubmitCalls)
	}

	@Test
	fun testTdlibDatabaseKeyMismatchShowsSpecificMessageAndDisablesRetry() {
		assertIdentifierRecoverableErrorMessage(
			RecoverableErrorDetail.TDLIB_DATABASE_KEY_MISMATCH,
			R.string.telegram_connector_login_tdlib_key_mismatch_message,
		)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsNotEnabled()
	}

	@Test
	fun testMissingApiCredentialsShowsSpecificMessageAndDisablesRetry() {
		assertIdentifierRecoverableErrorMessage(
			RecoverableErrorDetail.MISSING_API_CREDENTIALS,
			R.string.telegram_connector_login_api_credentials_missing_message,
		)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsNotEnabled()
	}

	@Test
	fun testDeviceKeystoreUnavailableShowsSpecificMessageAndDisablesRetry() {
		assertIdentifierRecoverableErrorMessage(
			RecoverableErrorDetail.DEVICE_KEYSTORE_UNAVAILABLE,
			R.string.telegram_connector_login_device_keystore_unavailable_message,
		)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsNotEnabled()
	}

	@Test
	fun testPersistedSessionIdentityUnverifiedShowsMessageAndSkipsConfirmation() {
		assertIdentifierRecoverableErrorMessage(
			RecoverableErrorDetail.PERSISTED_SESSION_IDENTITY_UNVERIFIED,
			R.string.telegram_connector_login_persisted_session_unverified_message,
		)
		composeRule.onAllNodesWithTag(TELEGRAM_LOGIN_CONFIRMATION_TAG)
			.assertCountEquals(0)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_STEP_TAG)
			.assertIsDisplayed()
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsNotEnabled()
	}

	private fun assertIdentifierRecoverableErrorMessage(
		detail: RecoverableErrorDetail,
		messageRes: Int,
	) {
		telegramAuthSession.stateAfterSubmitIdentifier =
			TelegramAuthState.RECOVERABLE_ERROR
		telegramAuthSession.detailAfterSubmitIdentifier = detail

		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_TAG)
			.performTextInput("+123456789")
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.performClick()
		composeRule.waitForIdle()

		assertLoginMessage(messageRes)
	}

	private fun assertLoginMessage(messageRes: Int) {
		composeRule.onNodeWithText(
			RuntimeEnvironment.getApplication().getString(messageRes),
		).assertIsDisplayed()
	}

	private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertEditableTextEquals(
		expected: String,
	) {
		assert(
			SemanticsMatcher.expectValue(
				SemanticsProperties.EditableText,
				AnnotatedString(expected),
			),
		)
	}

	private fun createViewModel(telegramAuthSession: TelegramAuthSession): StartupViewModel {
		val app = mock(Application::class.java)
		val accountManager = mock(AccountManager::class.java)
		val lifecycleManager = mock(LifecycleManager::class.java)
		val notificationManager = mock(AndroidNotificationManager::class.java)
		val eventBus = mock(EventBus::class.java)
		val featureFlags = mock(FeatureFlags::class.java)

		`when`(lifecycleManager.lifecycleState).thenReturn(LifecycleState.STOPPED)
		`when`(accountManager.hasDatabaseKey()).thenReturn(false)

		return StartupViewModel(
			app,
			accountManager,
			lifecycleManager,
			notificationManager,
			eventBus,
			Runnable::run,
			featureFlags,
			telegramAuthSession,
		)
	}

	private class FakeTelegramAuthSession : TelegramAuthSession {
		var currentAuthState = TelegramAuthState.CLOSED
		var currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		var stateAfterSubmitIdentifier = TelegramAuthState.IDENTIFIER_ENTRY
		var detailAfterSubmitIdentifier = RecoverableErrorDetail.NONE
		var stateAfterSubmitCode = TelegramAuthState.CODE_ENTRY
		var detailAfterSubmitCode = RecoverableErrorDetail.NONE
		var stateAfterResend = TelegramAuthState.CODE_ENTRY
		var detailAfterResend = RecoverableErrorDetail.NONE
		var lastIdentifier = ""
		var identifierSubmitCalls = 0
		var resendCodeCalls = 0
		var closeCalls = 0
		var qrRequestCalls = 0
		var qrLink: String? = null

		override fun getSnapshot() = TelegramAuthSession.Snapshot(
			currentAuthState,
			currentRecoverableErrorDetail,
			qrLink,
		)

		override fun start() {
			currentAuthState = TelegramAuthState.IDENTIFIER_ENTRY
			currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		}

		override fun submitIdentifier(identifier: String) {
			identifierSubmitCalls++
			lastIdentifier = identifier
			currentAuthState = stateAfterSubmitIdentifier
			currentRecoverableErrorDetail = detailAfterSubmitIdentifier
		}

		override fun submitCode(code: String) {
			currentAuthState = stateAfterSubmitCode
			currentRecoverableErrorDetail = detailAfterSubmitCode
		}

		override fun resendCode() {
			resendCodeCalls++
			currentAuthState = stateAfterResend
			currentRecoverableErrorDetail = detailAfterResend
		}

		override fun submitPassword(password: String) = Unit

		override fun requestQrCodeAuthentication() {
			qrRequestCalls++
			qrLink = "test-qr-link"
			currentAuthState = TelegramAuthState.QR_WAITING
		}

		override fun awaitQrAuthorizationUpdate() {
			currentAuthState = TelegramAuthState.READY
		}

		override fun close() {
			closeCalls++
			currentAuthState = TelegramAuthState.CLOSED
			currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		}
	}
}
