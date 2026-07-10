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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.briarproject.bramble.api.FeatureFlags
import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.event.EventBus
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState
import org.briarproject.bramble.api.settings.SettingsManager
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
			getOrAwaitValue(viewModel.getTelegramAuthState()),
		)

		composeRule.onNodeWithTag(TELEGRAM_LOGIN_BACK_TAG).performClick()
		composeRule.waitForIdle()

		assertEquals(1, telegramAuthSession.closeCalls)
		assertEquals(
			TelegramAuthState.CLOSED,
			getOrAwaitValue(viewModel.getTelegramAuthState()),
		)
		assertEquals(SIGNED_OUT, getOrAwaitValue(viewModel.getState()))
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
	fun testUnsupportedAuthStepShowsSpecificMessage() {
		assertIdentifierRecoverableErrorMessage(
			RecoverableErrorDetail.UNSUPPORTED_AUTH_STEP,
			"Telegram returned an account step Harbor does not support in this build. " +
				"Use Harbor password instead.",
		)
	}

	@Test
	fun testTdlibDatabaseKeyMismatchShowsSpecificMessageAndDisablesRetry() {
		assertIdentifierRecoverableErrorMessage(
			RecoverableErrorDetail.TDLIB_DATABASE_KEY_MISMATCH,
			"Telegram login cannot use the protected local Telegram database in this build. " +
				"Use Harbor password instead.",
		)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsNotEnabled()
	}

	@Test
	fun testDeviceKeystoreUnavailableShowsSpecificMessageAndDisablesRetry() {
		assertIdentifierRecoverableErrorMessage(
			RecoverableErrorDetail.DEVICE_KEYSTORE_UNAVAILABLE,
			"Telegram login in Harbor needs Android 6.0+ device-backed encryption. " +
				"Use Harbor password instead.",
		)
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.assertIsNotEnabled()
	}

	private fun assertIdentifierRecoverableErrorMessage(
		detail: RecoverableErrorDetail,
		message: String,
	) {
		telegramAuthSession.stateAfterSubmitIdentifier =
			TelegramAuthState.RECOVERABLE_ERROR
		telegramAuthSession.detailAfterSubmitIdentifier = detail

		composeRule.onNodeWithTag(TELEGRAM_LOGIN_IDENTIFIER_TAG)
			.performTextInput("+123456789")
		composeRule.onNodeWithTag(TELEGRAM_LOGIN_CONTINUE_TAG)
			.performClick()
		composeRule.waitForIdle()

		composeRule.onNodeWithText(message).assertIsDisplayed()
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
		val settingsManager = mock(SettingsManager::class.java)
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
			settingsManager,
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
		var lastIdentifier = ""
		var closeCalls = 0

		override fun getCurrentState(): TelegramAuthState = currentAuthState

		override fun getRecoverableErrorDetail(): RecoverableErrorDetail = currentRecoverableErrorDetail

		override fun start() {
			currentAuthState = TelegramAuthState.IDENTIFIER_ENTRY
			currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		}

		override fun submitIdentifier(identifier: String) {
			lastIdentifier = identifier
			currentAuthState = stateAfterSubmitIdentifier
			currentRecoverableErrorDetail = detailAfterSubmitIdentifier
		}

		override fun submitCode(code: String) {
			currentAuthState = stateAfterSubmitCode
		}

		override fun submitPassword(password: String) = Unit

		override fun close() {
			closeCalls++
			currentAuthState = TelegramAuthState.CLOSED
			currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		}
	}
}
