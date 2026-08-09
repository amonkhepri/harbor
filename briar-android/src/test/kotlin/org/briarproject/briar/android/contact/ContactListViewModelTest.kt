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
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.briarproject.briar.api.identity.AuthorManager
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.NONE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ContactListViewModelTest {

	@get:Rule
	val testRule = InstantTaskExecutorRule()

	@Test
	fun testTransientFailurePreservesLoadedConnectorThreads() {
		val connector = mock(ReadOnlyConnector::class.java)
		`when`(connector.source).thenReturn(ConnectorSources.TELEGRAM)
		`when`(connector.isEnabled()).thenReturn(true)
		`when`(connector.isAuthorized()).thenReturn(true)
		`when`(connector.getRecentThreads(20)).thenReturn(listOf(connectorThread()))
			.thenThrow(IllegalStateException())
		val viewModel = createViewModel(connector)

		viewModel.loadConnectorThreads()
		val loaded = getOrAwaitValue(viewModel.connectorThreadItems)
		viewModel.loadConnectorThreads()

		assertEquals(loaded, getOrAwaitValue(viewModel.connectorThreadItems))
		assertEquals(
			LOAD_FAILED,
			getOrAwaitValue(viewModel.connectorAvailabilityStates)[ConnectorSources.TELEGRAM],
		)
	}

	@Test
	fun testDeauthorizationClearsLoadedTelegramThreads() {
		val connector = mock(ReadOnlyConnector::class.java)
		`when`(connector.source).thenReturn(ConnectorSources.TELEGRAM)
		`when`(connector.isEnabled()).thenReturn(true)
		`when`(connector.isAuthorized()).thenReturn(true, false)
		`when`(connector.getRecentThreads(20)).thenReturn(listOf(connectorThread()))
		val viewModel = createViewModel(connector)

		viewModel.loadConnectorThreads()
		assertEquals(1, getOrAwaitValue(viewModel.connectorThreadItems).size)
		viewModel.loadConnectorThreads()

		assertEquals(emptyList<ConnectorInboxThreadItem>(), viewModel.connectorThreadItems.value)
		assertEquals(
			ACCOUNT_UNAVAILABLE,
			viewModel.connectorAvailabilityStates.value?.get(ConnectorSources.TELEGRAM),
		)
	}

	@Test
	fun testLoadsRegisteredConnectorsIntoSourceQualifiedRows() {
		val telegram = connector(ConnectorSources.TELEGRAM, connectorThread())
		val matrixThread = connectorThread(ConnectorSources.MATRIX, "!room:example")
		val matrix = connector(ConnectorSources.MATRIX, matrixThread)
		val viewModel = createViewModel(telegram, matrix)

		viewModel.loadConnectorThreads()

		val items = getOrAwaitValue(viewModel.connectorThreadItems)
		assertEquals(
			setOf("telegram:7", "matrix:!room:example"),
			items.map { it.stableId }.toSet(),
		)
		assertEquals(
			mapOf(ConnectorSources.TELEGRAM to NONE, ConnectorSources.MATRIX to NONE),
			getOrAwaitValue(viewModel.connectorAvailabilityStates),
		)
	}

	private fun connector(source: ConnectorSource, thread: ConnectorThread) =
		mock(ReadOnlyConnector::class.java).also { connector ->
			`when`(connector.source).thenReturn(source)
			`when`(connector.isEnabled()).thenReturn(true)
			`when`(connector.isAuthorized()).thenReturn(true)
			`when`(connector.getRecentThreads(20)).thenReturn(listOf(thread))
		}

	private fun connectorThread(
		source: ConnectorSource = ConnectorSources.TELEGRAM,
		threadId: String = "7",
	) = ConnectorThread(
		source,
		threadId,
		"",
		11,
		"",
		false,
		ConnectorMessageType.TEXT,
	)

	private fun createViewModel(vararg registeredConnectors: ReadOnlyConnector) = ContactListViewModel(
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
		object : ConnectorRegistry {
			override val connectors = registeredConnectors.toList()
		},
		ImmediateExecutor(),
	)
}
