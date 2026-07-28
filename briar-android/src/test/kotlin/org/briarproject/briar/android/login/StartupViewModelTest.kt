package org.briarproject.briar.android.login

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.briarproject.bramble.api.FeatureFlags
import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.event.EventBus
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState
import org.briarproject.bramble.api.lifecycle.event.LifecycleEvent
import org.briarproject.bramble.api.settings.SettingsManager
import org.briarproject.bramble.test.ImmediateExecutor
import org.briarproject.briar.android.login.StartupViewModel.State.SIGNED_OUT
import org.briarproject.briar.android.login.StartupViewModel.State.STARTED
import org.briarproject.briar.android.login.StartupViewModel.State.TELEGRAM_LOGIN
import org.briarproject.briar.android.viewmodel.LiveDataTestUtil.getOrAwaitValue
import org.briarproject.briar.api.android.AndroidNotificationManager
import org.briarproject.briar.api.telegram.TelegramAuthSession
import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthSession.Snapshot
import org.briarproject.briar.api.telegram.TelegramAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.ArrayDeque
import java.util.concurrent.Executor

class StartupViewModelTest {

	@get:Rule
	val testRule = InstantTaskExecutorRule()

	private lateinit var viewModel: StartupViewModel
	private lateinit var telegramAuthSession: FakeTelegramAuthSession
	private lateinit var accountManager: AccountManager

	@Before
	fun setUp() {
		telegramAuthSession = FakeTelegramAuthSession()
		viewModel = createViewModel(ImmediateExecutor())
	}

	private fun createViewModel(ioExecutor: Executor): StartupViewModel {
		val app = mock(Application::class.java)
		accountManager = mock(AccountManager::class.java)
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
			ioExecutor,
			settingsManager,
			featureFlags,
			telegramAuthSession,
		)
	}

	@Test
	fun testTelegramAuthCallsRunOnWorkerExecutor() {
		val executor = QueuedExecutor()
		viewModel = createViewModel(executor)

		viewModel.showTelegramLoginPlaceholder()
		assertEquals(0, telegramAuthSession.startCalls)
		executor.runNext()
		assertEquals(
			TelegramAuthState.IDENTIFIER_ENTRY,
			getOrAwaitValue(viewModel.telegramAuthSnapshot).authState,
		)

		viewModel.submitTelegramLoginIdentifier()
		assertEquals(0, telegramAuthSession.identifierSubmitCalls)
		executor.runNext()
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			getOrAwaitValue(viewModel.telegramAuthSnapshot).authState,
		)

		viewModel.resendTelegramLoginCode()
		assertEquals(0, telegramAuthSession.resendCodeCalls)
		executor.runNext()
		assertEquals(
			TelegramAuthState.CODE_ENTRY,
			getOrAwaitValue(viewModel.telegramAuthSnapshot).authState,
		)

		viewModel.submitTelegramLoginCode()
		assertEquals(0, telegramAuthSession.codeSubmitCalls)
		executor.runNext()
		assertEquals(
			TelegramAuthState.PASSWORD_ENTRY,
			getOrAwaitValue(viewModel.telegramAuthSnapshot).authState,
		)

		viewModel.submitTelegramLoginPassword()
		assertEquals(0, telegramAuthSession.passwordSubmitCalls)
		executor.runNext()
		assertEquals(TelegramAuthState.READY, getOrAwaitValue(viewModel.telegramAuthSnapshot).authState)

		viewModel.showPasswordFragment()
		assertEquals(0, telegramAuthSession.closeCalls)
		executor.runNext()
		assertEquals(TelegramAuthState.CLOSED, getOrAwaitValue(viewModel.telegramAuthSnapshot).authState)
	}

	@Test
	fun testTelegramAuthActionQueuedBeforeCancelIsSkipped() {
		val executor = QueuedExecutor()
		viewModel = createViewModel(executor)
		viewModel.setTelegramLoginIdentifier("+123456789")

		viewModel.submitTelegramLoginIdentifier()
		viewModel.showPasswordFragment()
		executor.runNext()

		assertEquals(0, telegramAuthSession.identifierSubmitCalls)
		assertEquals(0, telegramAuthSession.closeCalls)
		executor.runNext()
		assertEquals(1, telegramAuthSession.closeCalls)
		assertEquals(TelegramAuthState.CLOSED, getOrAwaitValue(viewModel.telegramAuthSnapshot).authState)
	}

	@Test
	fun testTelegramAuthActionCancelledInFlightDoesNotPostStaleState() {
		val observedSnapshots = mutableListOf<Snapshot>()
		viewModel.telegramAuthSnapshot.observeForever(observedSnapshots::add)
		telegramAuthSession.onIdentifierSubmitted = viewModel::showPasswordFragment

		viewModel.submitTelegramLoginIdentifier()

		assertEquals(1, telegramAuthSession.identifierSubmitCalls)
		assertTrue(observedSnapshots.none { it.authState == TelegramAuthState.CODE_ENTRY })
		assertEquals(TelegramAuthState.CLOSED, observedSnapshots.last().authState)
	}

	@Test
	fun testConfirmationUsesSubmittedIdentifierAfterInFlightEdit() {
		val executor = QueuedExecutor()
		viewModel = createViewModel(executor)
		viewModel.setTelegramLoginIdentifier(" submitted ")

		viewModel.submitTelegramLoginIdentifier()
		viewModel.setTelegramLoginIdentifier("unverified")
		executor.runNext()
		viewModel.completeTelegramLoginConfirmation()
		viewModel.completeTelegramLoginConfirmation()

		val field = StartupViewModel::class.java
			.getDeclaredField("pendingTelegramLinkedIdentity")
			.apply { isAccessible = true }
		assertEquals("submitted", field.get(viewModel))
	}

	@Test
	fun testAbandoningLocalSignInClearsPendingTelegramIdentity() {
		viewModel.setTelegramLoginIdentifier("submitted")
		viewModel.submitTelegramLoginIdentifier()
		viewModel.completeTelegramLoginConfirmation()

		viewModel.abandonPendingTelegramLinkedIdentity()

		val field = StartupViewModel::class.java
			.getDeclaredField("pendingTelegramLinkedIdentity")
			.apply { isAccessible = true }
		assertEquals("", field.get(viewModel))
	}

	@Test
	fun testPasswordValidationStoresIdentityAfterDatabaseStarts() {
		val executor = QueuedExecutor()
		viewModel = createViewModel(executor)
		setPrivateString("pendingTelegramLinkedIdentity", "first")
		viewModel.validatePassword("password")

		setPrivateString("pendingTelegramLinkedIdentity", "replacement")
		executor.runNext()

		assertEquals("", getPrivateString("lastTelegramLinkedIdentityStaged"))
		`when`(accountManager.hasDatabaseKey()).thenReturn(true)
		viewModel.eventOccurred(LifecycleEvent(LifecycleState.RUNNING))
		executor.runNext()

		assertEquals("first", getPrivateString("lastTelegramLinkedIdentityStaged"))
		assertEquals("replacement", getPrivateString("pendingTelegramLinkedIdentity"))
		assertEquals(STARTED, getOrAwaitValue(viewModel.state))
	}

	@Test
	fun testShowTelegramLoginPlaceholderClearsStaleAuthStateBeforeStart() {
		val executor = QueuedExecutor()
		viewModel = createViewModel(executor)
		telegramAuthSession.currentAuthState = TelegramAuthState.READY

		viewModel.showTelegramLoginPlaceholder()

		assertEquals(TelegramAuthState.CLOSED, getOrAwaitValue(viewModel.telegramAuthSnapshot).authState)
		assertEquals(0, telegramAuthSession.startCalls)
		executor.runNext()
		assertEquals(
			TelegramAuthState.IDENTIFIER_ENTRY,
			getOrAwaitValue(viewModel.telegramAuthSnapshot).authState,
		)
	}

	@Test
	fun testShowTelegramLoginIdentifierStepClearsConfirmationBeforeRestart() {
		val executor = QueuedExecutor()
		viewModel = createViewModel(executor)
		telegramAuthSession.currentAuthState = TelegramAuthState.CODE_ENTRY
		viewModel.showTelegramLoginPlaceholder()
		executor.runNext()
		viewModel.submitTelegramLoginIdentifier()
		executor.runNext()

		viewModel.showTelegramLoginIdentifierStep()

		assertEquals(TelegramAuthState.CLOSED, getOrAwaitValue(viewModel.telegramAuthSnapshot).authState)
		assertEquals(0, telegramAuthSession.closeCalls)
		executor.runNext()
		assertEquals(
			TelegramAuthState.IDENTIFIER_ENTRY,
			getOrAwaitValue(viewModel.telegramAuthSnapshot).authState,
		)
		assertEquals(1, telegramAuthSession.closeCalls)
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
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).authState,
		)
		assertEquals(SIGNED_OUT, getOrAwaitValue(viewModel.getState()))
		assertEquals(1, telegramAuthSession.closeCalls)
	}

	@Test
	fun testRejectedCodeResendRemainsInTelegramLoginFlow() {
		viewModel.showTelegramLoginPlaceholder()
		telegramAuthSession.stateAfterResend = TelegramAuthState.RECOVERABLE_ERROR
		telegramAuthSession.detailAfterResend =
			RecoverableErrorDetail.CODE_RESEND_FAILED
		viewModel.resendTelegramLoginCode()

		assertTrue(viewModel.isShowingTelegramLoginConfirmation())
	}

	@Test
	fun testTelegramLoginConfirmationUsesPublishedAuthSnapshot() {
		viewModel.showTelegramLoginPlaceholder()
		telegramAuthSession.stateAfterResend = TelegramAuthState.RECOVERABLE_ERROR
		telegramAuthSession.detailAfterResend = RecoverableErrorDetail.CODE_RESEND_FAILED
		viewModel.resendTelegramLoginCode()

		telegramAuthSession.currentRecoverableErrorDetail = RecoverableErrorDetail.NONE

		assertTrue(viewModel.isShowingTelegramLoginConfirmation())
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
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).authState,
		)
		assertEquals(
			RecoverableErrorDetail.NONE,
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).errorDetail,
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
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).authState,
		)
		assertEquals(
			RecoverableErrorDetail.NONE,
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).errorDetail,
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
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).authState,
		)
		assertEquals(
			RecoverableErrorDetail.NONE,
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).errorDetail,
		)
		assertEquals(TELEGRAM_LOGIN, getOrAwaitValue(viewModel.getState()))
		assertEquals(1, telegramAuthSession.startCalls)
		assertEquals(0, telegramAuthSession.closeCalls)

		viewModel.setTelegramLoginIdentifier("+123456789")
		assertEquals(
			TelegramAuthState.IDENTIFIER_ENTRY,
			getOrAwaitValue(viewModel.getTelegramAuthSnapshot()).authState,
		)
	}

	private fun getPrivateString(name: String): String {
		val field = StartupViewModel::class.java.getDeclaredField(name)
			.apply { isAccessible = true }
		return field.get(viewModel) as String
	}

	private fun setPrivateString(name: String, value: String) {
		val field = StartupViewModel::class.java.getDeclaredField(name)
			.apply { isAccessible = true }
		field.set(viewModel, value)
	}

	private class FakeTelegramAuthSession : TelegramAuthSession {
		var currentAuthState = TelegramAuthState.CLOSED
		var currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		var closeCalls = 0
		var startCalls = 0
		var identifierSubmitCalls = 0
		var onIdentifierSubmitted: () -> Unit = {}
		var resendCodeCalls = 0
		var stateAfterResend = TelegramAuthState.CODE_ENTRY
		var detailAfterResend = RecoverableErrorDetail.NONE
		var codeSubmitCalls = 0
		var passwordSubmitCalls = 0

		override fun getSnapshot() = TelegramAuthSession.Snapshot(
			currentAuthState,
			currentRecoverableErrorDetail,
		)

		override fun start() {
			startCalls++
			currentAuthState = TelegramAuthState.IDENTIFIER_ENTRY
		}

		override fun submitIdentifier(identifier: String) {
			identifierSubmitCalls++
			currentAuthState = TelegramAuthState.CODE_ENTRY
			onIdentifierSubmitted()
		}

		override fun submitCode(code: String) {
			codeSubmitCalls++
			currentAuthState = TelegramAuthState.PASSWORD_ENTRY
		}

		override fun resendCode() {
			resendCodeCalls++
			currentAuthState = stateAfterResend
			currentRecoverableErrorDetail = detailAfterResend
		}

		override fun submitPassword(password: String) {
			passwordSubmitCalls++
			currentAuthState = TelegramAuthState.READY
		}

		override fun requestQrCodeAuthentication() = Unit

		override fun awaitQrAuthorizationUpdate() = Unit

		override fun close() {
			closeCalls++
			currentAuthState = TelegramAuthState.CLOSED
			currentRecoverableErrorDetail = RecoverableErrorDetail.NONE
		}
	}

	private class QueuedExecutor : Executor {
		private val tasks = ArrayDeque<Runnable>()

		override fun execute(command: Runnable) {
			tasks.add(command)
		}

		fun runNext() = tasks.remove().run()
	}
}
