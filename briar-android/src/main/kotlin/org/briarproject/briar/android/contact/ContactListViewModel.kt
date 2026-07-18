package org.briarproject.briar.android.contact

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.briarproject.bramble.api.connection.ConnectionRegistry
import org.briarproject.bramble.api.contact.ContactManager
import org.briarproject.bramble.api.contact.event.PendingContactAddedEvent
import org.briarproject.bramble.api.contact.event.PendingContactRemovedEvent
import org.briarproject.bramble.api.db.DatabaseExecutor
import org.briarproject.bramble.api.db.DbException
import org.briarproject.bramble.api.db.TransactionManager
import org.briarproject.bramble.api.event.Event
import org.briarproject.bramble.api.event.EventBus
import org.briarproject.bramble.api.lifecycle.IoExecutor
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.bramble.api.system.AndroidExecutor
import org.briarproject.briar.api.android.AndroidNotificationManager
import org.briarproject.briar.api.conversation.ConversationManager
import org.briarproject.briar.api.identity.AuthorManager
import org.briarproject.briar.api.telegram.TelegramConnector
import java.util.concurrent.Executor
import javax.inject.Inject

internal class ContactListViewModel @Inject constructor(
	application: Application,
	@DatabaseExecutor dbExecutor: Executor,
	lifecycleManager: LifecycleManager,
	db: TransactionManager,
	androidExecutor: AndroidExecutor,
	contactManager: ContactManager,
	authorManager: AuthorManager,
	conversationManager: ConversationManager,
	connectionRegistry: ConnectionRegistry,
	eventBus: EventBus,
	private val notificationManager: AndroidNotificationManager,
	private val telegramConnector: TelegramConnector,
	@IoExecutor private val ioExecutor: Executor,
) : ContactsViewModel(
	application,
	dbExecutor,
	lifecycleManager,
	db,
	androidExecutor,
	contactManager,
	authorManager,
	conversationManager,
	connectionRegistry,
	eventBus,
) {

	private val _hasPendingContacts = MutableLiveData<Boolean>()
	private val _telegramThreadItems = MutableLiveData<List<TelegramInboxThreadItem>>(emptyList())
	private val _telegramAvailabilityState =
		MutableLiveData(TelegramInboxAvailabilityState.NONE)

	val hasPendingContacts: LiveData<Boolean> = _hasPendingContacts
	val telegramThreadItems: LiveData<List<TelegramInboxThreadItem>> = _telegramThreadItems
	val telegramAvailabilityState: LiveData<TelegramInboxAvailabilityState> =
		_telegramAvailabilityState

	override fun eventOccurred(event: Event) {
		super.eventOccurred(event)
		if (event is PendingContactAddedEvent || event is PendingContactRemovedEvent) {
			checkForPendingContacts()
		}
	}

	fun isTelegramConnectorEnabled(): Boolean = telegramConnector.isEnabled()

	fun checkForPendingContacts() {
		runOnDbThread {
			try {
				_hasPendingContacts.postValue(contactManager.pendingContacts.isNotEmpty())
			} catch (e: DbException) {
				handleException(e)
			}
		}
	}

	fun clearAllContactNotifications() = notificationManager.clearAllContactNotifications()

	fun clearAllContactAddedNotifications() = notificationManager.clearAllContactAddedNotifications()

	fun loadTelegramThreads() {
		if (!telegramConnector.isEnabled()) {
			_telegramAvailabilityState.value = TelegramInboxAvailabilityState.NONE
			_telegramThreadItems.value = emptyList()
			return
		}
		_telegramAvailabilityState.value = TelegramInboxAvailabilityState.LOADING
		ioExecutor.execute {
			try {
				if (!telegramConnector.isAuthorized()) {
					_telegramAvailabilityState.postValue(
						telegramAvailabilityStateFor(true, false, false, false),
					)
					return@execute
				}
				val chats = telegramConnector.getRecentChats(20)
				_telegramAvailabilityState.postValue(
					telegramAvailabilityStateFor(true, true, false, chats.isNotEmpty()),
				)
				_telegramThreadItems.postValue(chats.map(::TelegramInboxThreadItem))
			} catch (e: RuntimeException) {
				_telegramAvailabilityState.postValue(
					telegramAvailabilityStateFor(true, true, true, false),
				)
			}
		}
	}

	companion object {
		@JvmStatic
		fun telegramAvailabilityStateFor(
			connectorEnabled: Boolean,
			authorized: Boolean,
			loadFailed: Boolean,
			hasRows: Boolean,
		): TelegramInboxAvailabilityState = when {
			!connectorEnabled || hasRows -> TelegramInboxAvailabilityState.NONE
			loadFailed -> TelegramInboxAvailabilityState.LOAD_FAILED
			!authorized -> TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE
			else -> TelegramInboxAvailabilityState.EMPTY
		}
	}
}
