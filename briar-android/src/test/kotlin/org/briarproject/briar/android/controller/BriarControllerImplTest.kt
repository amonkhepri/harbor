package org.briarproject.briar.android.controller

import android.app.Activity
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager
import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.bramble.api.settings.SettingsManager
import org.briarproject.briar.android.BriarService
import org.briarproject.briar.android.BriarService.BriarServiceConnection
import org.briarproject.briar.android.controller.handler.ResultHandler
import org.briarproject.briar.api.android.DozeWatchdog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.Executor

class BriarControllerImplTest {

	@Test
	fun testInterruptedShutdownWaitCompletesBeforeAccountDeletion() {
		val serviceConnection = mock(BriarServiceConnection::class.java)
		val accountManager = mock(AccountManager::class.java)
		val wakeLockManager = mock(AndroidWakeLockManager::class.java)
		val service = mock(BriarService::class.java)
		val binder = mock(BriarService.BriarBinder::class.java)
		`when`(serviceConnection.waitForBinder()).thenReturn(binder)
		`when`(binder.service).thenReturn(service)
		`when`(service.waitForShutdown())
			.thenThrow(InterruptedException())
			.thenAnswer { null }

		val controller = BriarControllerImpl(
			serviceConnection,
			accountManager,
			mock(LifecycleManager::class.java),
			mock(Executor::class.java),
			mock(SettingsManager::class.java),
			mock(DozeWatchdog::class.java),
			wakeLockManager,
			mock(Activity::class.java),
		)
		var handled = false
		controller.signOut(ResultHandler { handled = true }, true)
		val signOut = ArgumentCaptor.forClass(Runnable::class.java)
		verify(wakeLockManager).executeWakefully(signOut.capture(), eq("SignOut"))

		try {
			signOut.value.run()

			val order = inOrder(service, accountManager)
			order.verify(service, times(2)).waitForShutdown()
			order.verify(accountManager).deleteAccount()
			assertTrue(handled)
			assertTrue(Thread.currentThread().isInterrupted)
		} finally {
			Thread.interrupted()
		}
	}
}
