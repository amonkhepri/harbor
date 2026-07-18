package org.briarproject.briar.android.contact

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.briarproject.bramble.api.connection.ConnectionRegistry
import org.briarproject.bramble.api.contact.ContactManager
import org.briarproject.bramble.api.db.TransactionManager
import org.briarproject.bramble.api.event.EventBus
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.bramble.api.system.AndroidExecutor
import org.briarproject.bramble.test.ImmediateExecutor
import org.briarproject.briar.android.viewmodel.LiveDataTestUtil.getOrAwaitValue
import org.briarproject.briar.api.android.AndroidNotificationManager
import org.briarproject.briar.api.conversation.ConversationManager
import org.briarproject.briar.api.identity.AuthorManager
import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramConnector
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.NONE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ContactListViewModelTest {

	@get:Rule
	val testRule = InstantTaskExecutorRule()

	@Test
	fun testTelegramAvailabilityStateSelection() {
		val states = listOf(
			ContactListViewModel.telegramAvailabilityStateFor(false, false, false, false),
			ContactListViewModel.telegramAvailabilityStateFor(true, false, false, false),
			ContactListViewModel.telegramAvailabilityStateFor(true, true, false, false),
			ContactListViewModel.telegramAvailabilityStateFor(true, true, true, false),
			ContactListViewModel.telegramAvailabilityStateFor(true, true, false, true),
		)
		assertEquals(listOf(NONE, ACCOUNT_UNAVAILABLE, EMPTY, LOAD_FAILED, NONE), states)
	}

	@Test
	fun testTransientFailurePreservesLoadedTelegramThreads() {
		val connector = mock(TelegramConnector::class.java)
		`when`(connector.isEnabled()).thenReturn(true)
		`when`(connector.isAuthorized()).thenReturn(true)
		`when`(connector.getRecentChats(20))
			.thenReturn(listOf(TelegramChat(7, "", 11)))
			.thenThrow(IllegalStateException())
		val viewModel = createViewModel(connector)

		viewModel.loadTelegramThreads()
		val loaded = getOrAwaitValue(viewModel.telegramThreadItems)
		viewModel.loadTelegramThreads()

		assertEquals(loaded, getOrAwaitValue(viewModel.telegramThreadItems))
		assertEquals(LOAD_FAILED, getOrAwaitValue(viewModel.telegramAvailabilityState))
	}

	private fun createViewModel(connector: TelegramConnector) = ContactListViewModel(
		mock(Application::class.java),
		ImmediateExecutor(),
		mock(LifecycleManager::class.java),
		mock(TransactionManager::class.java),
		mock(AndroidExecutor::class.java),
		mock(ContactManager::class.java),
		mock(AuthorManager::class.java),
		mock(ConversationManager::class.java),
		mock(ConnectionRegistry::class.java),
		mock(EventBus::class.java),
		mock(AndroidNotificationManager::class.java),
		connector,
		ImmediateExecutor(),
	)
}
