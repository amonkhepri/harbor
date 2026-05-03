package org.briarproject.briar.android.login

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.briarproject.bramble.api.FeatureFlags
import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.event.EventBus
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState
import org.briarproject.bramble.api.settings.SettingsManager
import org.briarproject.bramble.test.ImmediateExecutor
import org.briarproject.briar.android.login.StartupViewModel.State.SIGNED_OUT
import org.briarproject.briar.android.login.StartupViewModel.State.TELEGRAM_LOGIN
import org.briarproject.briar.android.viewmodel.LiveDataTestUtil.getOrAwaitValue
import org.briarproject.briar.api.android.AndroidNotificationManager
import org.briarproject.briar.api.telegram.TelegramAuthSession
import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class StartupViewModelTest {

	@get:Rule
	val testRule = InstantTaskExecutorRule()

	private lateinit var viewModel: StartupViewModel
	private lateinit var telegramAuthSession: FakeTelegramAuthSession

	@Before
	fun setUp() {
		telegramAuthSession = FakeTelegramAuthSession()
		val app = mock(Application::class.java)
		val accountManager = mock(AccountManager::class.java)
		val lifecycleManager = mock(LifecycleManager::class.java)
		val notificationManager = mock(AndroidNotificationManager::class.java)
		val eventBus = mock(EventBus::class.java)
		val settingsManager = mock(SettingsManager::class.java)
		val featureFlags = mock(FeatureFlags::class.java)

		`when`(lifecycleManager.lifecycleState).thenReturn(LifecycleState.STOPPED)
		`when`(accountManager.hasDatabaseKey()).thenReturn(false)

		viewModel = StartupViewModel(
			app,
			accountManager,
			lifecycleManager,
			notificationManager,
			eventBus,
			ImmediateExecutor(),
			settingsManager,
			featureFlags,
			telegramAuthSession,
		)
	}

	@Test
	fun testShowPasswordFragmentClearsTelegramIdentifierOnFallback() {
		viewModel.setTelegramLoginIdentifier(" +123456789 ")
		viewModel.setTelegramLoginCode("12345")
		viewModel.setTelegramLoginPassword("secret")

		viewModel.showPasswordFragment()

		assertEquals("", viewModel.getTelegramLoginIdentifier())
		assertEquals("", viewModel.getTelegramLoginCode())
		assertEquals("", viewModel.getTelegramLoginPassword())
		assertEquals(
			TelegramAuthState.CLOSED,
			getOrAwaitValue(viewModel.getTelegramAuthState()),
		)
		assertEquals(SIGNED_OUT, getOrAwaitValue(viewModel.getState()))
		assertEquals(1, telegramAuthSession.closeCalls)
	}

	@Test
	fun testShowPasswordFragmentClearsInvalidPasswordRecoverableErrorOnFallback() {
		viewModel.setTelegramLoginIdentifier(" +123456789 ")
		viewModel.setTelegramLoginCode("12345")
		viewModel.setTelegramLoginPassword("secret")
		telegramAuthSession.currentRecoverableErrorDetail =
			RecoverableErrorDetail.INVALID_PASSWORD
		telegramAuthSession.currentAuthState =
			TelegramAuthState.RECOVERABLE_ERROR

		viewModel.showPasswordFragment()

		assertEquals("", viewModel.getTelegramLoginIdentifier())
		assertEquals("", viewModel.getTelegramLoginCode())
		assertEquals("", viewModel.getTelegramLoginPassword())
		assertEquals(
			TelegramAuthState.CLOSED,
			getOrAwaitValue(viewModel.getTelegramAuthState()),
		)
		assertEquals(
			RecoverableErrorDetail.NONE,
			viewModel.getTelegramRecoverableErrorDetail(),
		)
		assertEquals(SIGNED_OUT, getOrAwaitValue(viewModel.getState()))
		assertEquals(1, telegramAuthSession.closeCalls)
	}

	@Test
	fun testShowTelegramLoginPlaceholderRestartsIdentifierEntryAfterFallback() {
		viewModel.setTelegramLoginIdentifier(" +123456789 ")
		viewModel.setTelegramLoginCode("12345")
		viewModel.setTelegramLoginPassword("secret")
		telegramAuthSession.currentRecoverableErrorDetail =
			RecoverableErrorDetail.INVALID_PASSWORD
		telegramAuthSession.currentAuthState =
			TelegramAuthState.RECOVERABLE_ERROR

		viewModel.showPasswordFragment()
		viewModel.showTelegramLoginPlaceholder()

		assertEquals("", viewModel.getTelegramLoginIdentifier())
		assertEquals("", viewModel.getTelegramLoginCode())
		assertEquals("", viewModel.getTelegramLoginPassword())
		assertEquals(
			TelegramAuthState.IDENTIFIER_ENTRY,
			getOrAwaitValue(viewModel.getTelegramAuthState()),
		)
		assertEquals(
			RecoverableErrorDetail.NONE,
			viewModel.getTelegramRecoverableErrorDetail(),
		)
		assertEquals(TELEGRAM_LOGIN, getOrAwaitValue(viewModel.getState()))
		assertEquals(1, telegramAuthSession.closeCalls)
		assertEquals(1, telegramAuthSession.startCalls)
	}

	@Test
	fun testRetryTelegramLoginAfterStartTimeoutFallback() {
		telegramAuthSession.currentAuthState =
			TelegramAuthState.RECOVERABLE_ERROR
		telegramAuthSession.currentRecoverableErrorDetail =
			RecoverableErrorDetail.NONE

		viewModel.showTelegramLoginPlaceholder()

		assertEquals(
			TelegramAuthState.IDENTIFIER_ENTRY,
			getOrAwaitValue(viewModel.getTelegramAuthState()),
		)
		assertEquals(
			RecoverableErrorDetail.NONE,
			viewModel.getTelegramRecoverableErrorDetail(),
		)
		assertEquals(TELEGRAM_LOGIN, getOrAwaitValue(viewModel.getState()))
		assertEquals(1, telegramAuthSession.startCalls)
		assertEquals(0, telegramAuthSession.closeCalls)

		viewModel.setTelegramLoginIdentifier("+123456789")
		assertEquals(
			TelegramAuthState.IDENTIFIER_ENTRY,
			getOrAwaitValue(viewModel.getTelegramAuthState()),
		)
	}

	private class FakeTelegramAuthSession : TelegramAuthSession {
		var currentAuthState = TelegramAuthState.CLOSED
		var currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		var closeCalls = 0
		var startCalls = 0

		override fun getCurrentState(): TelegramAuthState = currentAuthState

		override fun getRecoverableErrorDetail(): RecoverableErrorDetail =
			currentRecoverableErrorDetail

		override fun start() {
			startCalls++
			currentAuthState = TelegramAuthState.IDENTIFIER_ENTRY
		}

		override fun submitIdentifier(identifier: String) = Unit

		override fun submitCode(code: String) = Unit

		override fun submitPassword(password: String) = Unit

		override fun close() {
			closeCalls++
			currentAuthState = TelegramAuthState.CLOSED
			currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		}
	}
}
