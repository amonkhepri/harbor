package org.briarproject.briar.android.contact

import android.app.Application
import androidx.annotation.UiThread
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
import org.briarproject.briar.api.connector.ConnectorRoomInvitationAccepter
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.conversation.ConversationManager
import org.briarproject.briar.api.identity.AuthorManager
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOADING
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.NONE
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.SESSION_EXPIRED
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
	private val matrixAuthSession: MatrixAuthSession,
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
	private val _sourceFilter = MutableLiveData<InboxSourceFilter>(InboxSourceFilter.All)

	val hasPendingContacts: LiveData<Boolean> = _hasPendingContacts
	val connectorThreadItems: LiveData<List<ConnectorInboxThreadItem>> = _connectorThreadItems
	val connectorAvailabilityStates:
		LiveData<Map<ConnectorSource, ConnectorInboxAvailabilityState>> =
		_connectorAvailabilityStates
	val sourceFilter: LiveData<InboxSourceFilter> = _sourceFilter

	private val roomObservers = ConnectorInboxRoomObservers(::onRemoteConnectorActivity)

	/** Set on the UI thread when remote activity arrives while a load is in flight. */
	private var remoteRefreshPending = false

	fun setSourceFilter(filter: InboxSourceFilter) {
		if (_sourceFilter.value != filter) {
			_sourceFilter.value = filter
		}
	}

	override fun onCleared() {
		super.onCleared()
		roomObservers.close()
	}

	override fun eventOccurred(event: Event) {
		super.eventOccurred(event)
		if (event is PendingContactAddedEvent || event is PendingContactRemovedEvent) {
			checkForPendingContacts()
		}
	}

	private var acceptingInvitation = false

	fun prepareAcceptInvitation(
		item: ConnectorInboxThreadItem,
	): ConnectorRoomInvitationAccepter.PreparedAccept? {
		if (!item.isInvited || acceptingInvitation) return null
		val connector = connectorRegistry.connectors.firstOrNull {
			it.source == item.connectorSource && it.isEnabled() && it.isAuthorized()
		} as? ConnectorRoomInvitationAccepter ?: return null
		return connector.prepareAcceptInvitation(item.connectorThreadId)
	}

	/** Owned by the retained ViewModel so rotation cannot enqueue another join. */
	fun acceptInvitation(
		prepared: ConnectorRoomInvitationAccepter.PreparedAccept,
		onComplete: androidx.core.util.Consumer<Boolean>,
	) {
		if (acceptingInvitation) return
		acceptingInvitation = true
		ioExecutor.execute {
			val success = try {
				prepared.isCurrent() && prepared.accept()
			} catch (e: RuntimeException) {
				false
			}
			androidExecutor.runOnUiThread {
				acceptingInvitation = false
				if (success && prepared.isCurrent()) refreshAfterRemoteActivity()
				onComplete.accept(success && prepared.isCurrent())
			}
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
		val (connectors, disabled) = connectorRegistry.connectors.partition { it.isEnabled() }
		disabled.forEach { roomObservers.clear(it.source) }
		if (connectors.isEmpty()) {
			_connectorAvailabilityStates.value = emptyMap()
			_connectorThreadItems.value = emptyList()
			if (_sourceFilter.value != InboxSourceFilter.All) {
				_sourceFilter.value = InboxSourceFilter.All
			}
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
					if (connector.source == ConnectorSources.MATRIX) matrixAuthSession.start()
					if (connector.isSessionExpired()) {
						states[connector.source] = SESSION_EXPIRED
						roomObservers.clear(connector.source)
						return@forEach
					}
					if (!connector.isAuthorized()) {
						states[connector.source] = ACCOUNT_UNAVAILABLE
						roomObservers.clear(connector.source)
						return@forEach
					}
					val fence = sessionFence(connector.source)
					val threads = connector.getRecentThreads(20)
					states[connector.source] = when {
						threads.isNotEmpty() -> NONE
						connector.isSessionExpired() -> SESSION_EXPIRED
						else -> EMPTY
					}
					threads.mapTo(items, ::GenericConnectorInboxThreadItem)
					roomObservers.update(connector, fence, threads)
				} catch (e: RuntimeException) {
					states[connector.source] = LOAD_FAILED
					items.addAll(previousItems[connector.source].orEmpty())
				}
			}
			_connectorThreadItems.postValue(items)
			_connectorAvailabilityStates.postValue(states)
			// Queued after the posted values, so the LOADING guard is already cleared.
			androidExecutor.runOnUiThread(Runnable(::runPendingRemoteRefresh))
		}
	}

	/**
	 * Identity of the current Matrix auth snapshot. Every sign-in, restore or expiry
	 * installs a new snapshot instance, so subscriptions made under an older session are
	 * replaced instead of silently outliving it. Sign-out is fenced by the unauthorized path.
	 */
	private fun sessionFence(source: ConnectorSource): Any? =
		if (source == ConnectorSources.MATRIX) matrixAuthSession.getSnapshot() else null

	/** Called from SDK callback threads; coalesces onto the UI thread. */
	private fun onRemoteConnectorActivity() {
		androidExecutor.runOnUiThread(Runnable(::refreshAfterRemoteActivity))
	}

	@UiThread
	private fun refreshAfterRemoteActivity() {
		if (roomObservers.isClosed) return
		if (_connectorAvailabilityStates.value.orEmpty().values.any { it == LOADING }) {
			remoteRefreshPending = true
		} else {
			loadConnectorThreads()
		}
	}

	@UiThread
	private fun runPendingRemoteRefresh() {
		if (!remoteRefreshPending) return
		remoteRefreshPending = false
		if (!roomObservers.isClosed) loadConnectorThreads()
	}
}
