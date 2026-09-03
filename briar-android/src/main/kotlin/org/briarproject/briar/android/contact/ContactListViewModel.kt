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
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.conversation.ConversationManager
import org.briarproject.briar.api.identity.AuthorManager
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOADING
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.NONE
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
	private val connectorRegistry: ConnectorRegistry,
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
	private val _connectorThreadItems = MutableLiveData<List<ConnectorInboxThreadItem>>(emptyList())
	private val _connectorAvailabilityStates =
		MutableLiveData<Map<ConnectorSource, ConnectorInboxAvailabilityState>>(emptyMap())

	val hasPendingContacts: LiveData<Boolean> = _hasPendingContacts
	val connectorThreadItems: LiveData<List<ConnectorInboxThreadItem>> = _connectorThreadItems
	val connectorAvailabilityStates:
		LiveData<Map<ConnectorSource, ConnectorInboxAvailabilityState>> =
		_connectorAvailabilityStates

	override fun eventOccurred(event: Event) {
		super.eventOccurred(event)
		if (event is PendingContactAddedEvent || event is PendingContactRemovedEvent) {
			checkForPendingContacts()
		}
	}

	fun isAnyConnectorEnabled(): Boolean = connectorRegistry.connectors.any { it.isEnabled() }

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

	fun loadConnectorThreads() {
		if (_connectorAvailabilityStates.value.orEmpty().values.any { it == LOADING }) return
		val connectors = connectorRegistry.connectors.filter { it.isEnabled() }
		if (connectors.isEmpty()) {
			_connectorAvailabilityStates.value = emptyMap()
			_connectorThreadItems.value = emptyList()
			return
		}
		val previousItems = _connectorThreadItems.value.orEmpty().groupBy {
			it.connectorSource
		}
		_connectorAvailabilityStates.value = connectors.associate {
			it.source to LOADING
		}
		ioExecutor.execute {
			val items = ArrayList<ConnectorInboxThreadItem>()
			val states = LinkedHashMap<ConnectorSource, ConnectorInboxAvailabilityState>()
			connectors.forEach { connector ->
				try {
					if (!connector.isAuthorized()) {
						states[connector.source] = ACCOUNT_UNAVAILABLE
						return@forEach
					}
					val threads = connector.getRecentThreads(20)
					states[connector.source] = if (threads.isEmpty()) EMPTY else NONE
					threads.mapTo(items, ::GenericConnectorInboxThreadItem)
				} catch (e: RuntimeException) {
					states[connector.source] = LOAD_FAILED
					items.addAll(previousItems[connector.source].orEmpty())
				}
			}
			_connectorThreadItems.postValue(items)
			_connectorAvailabilityStates.postValue(states)
		}
	}
}
