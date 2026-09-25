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
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.briarproject.briar.matrix.MatrixAuthSessionImpl
import org.briarproject.briar.matrix.MatrixRoomConnector
import org.briarproject.briar.matrix.MatrixStoreConfiguration
import org.briarproject.briar.matrix.MatrixStoreConfigurationProvider
import org.briarproject.briar.matrix.DirectMatrixClientSessionDelegateFactory
import org.briarproject.briar.matrix.ReflectiveMatrixHomeserverDiscoveryClient
import org.junit.After
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ContactListViewModelTest {

	@get:Rule
	val testRule = InstantTaskExecutorRule()

	@get:Rule
	val testFolder = TemporaryFolder()

	@After
	fun resetSdk() = FakeMatrixSdkState.reset()

	@Test
	fun `inbox restores saved session on IO and refresh preserves READY and sign-out`() {
		val fixture = MatrixInboxFixture()
		fixture.saveSession()
		// saveSession()'s login() quarantines the store directory in the discovery
		// client's process-lifetime openedStores guard (real double-open protection).
		// A fresh app process would start with an empty guard; this in-process test
		// simulates that restart boundary by resetting it before the restore below.
		clearOpenedStoresGuard()
		val session = fixture.session()
		val io = QueuedIoExecutor()
		val viewModel = createViewModel(
			fixture.connector(session),
			matrixAuthSession = session,
			ioExecutor = io,
		)
		val caller = Thread.currentThread()
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)

		viewModel.loadConnectorThreads()
		assertEquals(0, fixture.storeReads)
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
		assertEquals(
			ConnectorInboxAvailabilityState.LOADING,
			viewModel.connectorAvailabilityStates.value?.get(ConnectorSources.MATRIX),
		)
		io.runNext()

		assertNotEquals(caller, fixture.storeThread)
		assertEquals(MatrixAuthState.READY, session.getSnapshot().authState)
		assertEquals("matrix:!room:example", viewModel.connectorThreadItems.value?.single()?.stableId)
		assertEquals(NONE, viewModel.connectorAvailabilityStates.value?.get(ConnectorSources.MATRIX))
		viewModel.loadConnectorThreads()
		io.runNext()
		assertEquals(1, fixture.storeReads)
		assertEquals(1, FakeMatrixSdkState.restoreSessionCallCount)

		// Sign-out wins even when an inbox refresh was queued while READY.
		viewModel.loadConnectorThreads()
		session.logout()
		val readsAfterLogout = fixture.storeReads
		io.runNext()
		viewModel.loadConnectorThreads()
		io.runNext()
		assertEquals(readsAfterLogout, fixture.storeReads)
		assertEquals(1, FakeMatrixSdkState.restoreSessionCallCount)
		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, session.getSnapshot().authState)
		assertEquals(emptyList<ConnectorInboxThreadItem>(), viewModel.connectorThreadItems.value)
		assertEquals(
			ACCOUNT_UNAVAILABLE,
			viewModel.connectorAvailabilityStates.value?.get(ConnectorSources.MATRIX),
		)
	}

	@Test
	fun `disabled Matrix never reads store or restores`() {
		val fixture = MatrixInboxFixture()
		val session = fixture.session()
		val matrix = connector(ConnectorSources.MATRIX, connectorThread(ConnectorSources.MATRIX))
		`when`(matrix.isEnabled()).thenReturn(false)
		val telegram = connector(ConnectorSources.TELEGRAM, connectorThread())
		val viewModel = createViewModel(matrix, telegram, matrixAuthSession = session)

		viewModel.loadConnectorThreads()

		assertEquals(0, fixture.storeReads)
		assertEquals(MatrixAuthState.CLOSED, session.getSnapshot().authState)
		assertEquals("telegram:7", viewModel.connectorThreadItems.value?.single()?.stableId)
		assertEquals(
			mapOf(ConnectorSources.TELEGRAM to NONE),
			viewModel.connectorAvailabilityStates.value,
		)
	}

	@Test
	fun `missing saved session leaves setup available and refresh preserves credential entry`() {
		val fixture = MatrixInboxFixture()
		val session = fixture.session()
		val viewModel = createViewModel(fixture.connector(session), matrixAuthSession = session)

		viewModel.loadConnectorThreads()
		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, session.getSnapshot().authState)
		session.submitHomeserver("matrix.invalid")
		viewModel.loadConnectorThreads()

		assertEquals(MatrixAuthState.CREDENTIAL_ENTRY, session.getSnapshot().authState)
		assertEquals(1, fixture.storeReads)
		assertEquals(emptyList<ConnectorInboxThreadItem>(), viewModel.connectorThreadItems.value)
		assertEquals(
			ACCOUNT_UNAVAILABLE,
			viewModel.connectorAvailabilityStates.value?.get(ConnectorSources.MATRIX),
		)
	}

	@Test
	fun `invalid saved session reports unavailable and still loads Telegram`() {
		val fixture = MatrixInboxFixture()
		fixture.saveSession()
		// A different protected-store key cannot decrypt the saved credentials.
		fixture.configuration = MatrixStoreConfiguration(
			fixture.configuration.directory,
			"matrix-sdk",
			ByteArray(32) { 42 },
		)
		val session = fixture.session()
		val telegram = connector(ConnectorSources.TELEGRAM, connectorThread())
		val viewModel = createViewModel(fixture.connector(session), telegram, matrixAuthSession = session)

		viewModel.loadConnectorThreads()

		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, session.getSnapshot().authState)
		assertEquals("telegram:7", viewModel.connectorThreadItems.value?.single()?.stableId)
		assertEquals(
			mapOf(ConnectorSources.MATRIX to ACCOUNT_UNAVAILABLE, ConnectorSources.TELEGRAM to NONE),
			viewModel.connectorAvailabilityStates.value,
		)
	}

	@Test
	fun `store exception stays inside Matrix failure boundary and finishes other source loading`() {
		val fixture = MatrixInboxFixture()
		fixture.failStoreRead = true
		val session = fixture.session()
		val telegram = connector(ConnectorSources.TELEGRAM, connectorThread())
		val io = QueuedIoExecutor()
		val viewModel = createViewModel(
			fixture.connector(session),
			telegram,
			matrixAuthSession = session,
			ioExecutor = io,
		)

		viewModel.loadConnectorThreads()
		io.runNext()

		assertEquals("telegram:7", viewModel.connectorThreadItems.value?.single()?.stableId)
		assertEquals(
			mapOf(ConnectorSources.MATRIX to LOAD_FAILED, ConnectorSources.TELEGRAM to NONE),
			viewModel.connectorAvailabilityStates.value,
		)
	}

	private inner class MatrixInboxFixture : MatrixStoreConfigurationProvider {
		var configuration = MatrixStoreConfiguration(
			testFolder.newFolder(),
			"matrix-sdk",
			ByteArray(32) { it.toByte() },
		)
		var storeReads = 0
		var storeThread: Thread? = null
		var failStoreRead = false
		private var client = newClient()

		override fun getStoreConfiguration(): MatrixStoreConfiguration {
			storeReads++
			storeThread = Thread.currentThread()
			check(!failStoreRead)
			return configuration
		}

		fun saveSession() {
			val saved = session()
			saved.start()
			saved.submitHomeserver("matrix.invalid")
			saved.submitCredentials("fixture-user", "fixture-password")
			assertEquals(MatrixAuthState.READY, saved.getSnapshot().authState)
			saved.close()
			storeReads = 0
		}

		fun session(): MatrixAuthSessionImpl {
			client = newClient()
			return MatrixAuthSessionImpl(client, client, this)
		}

		fun connector(session: MatrixAuthSession): MatrixRoomConnector {
			FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example", "Room"))
			return MatrixRoomConnector(client, session, client)
		}

		private fun newClient() = ReflectiveMatrixHomeserverDiscoveryClient(
			sessionDelegateFactory = DirectMatrixClientSessionDelegateFactory(),
		)
	}

	private class QueuedIoExecutor : Executor {
		private val tasks = ArrayDeque<Runnable>()
		override fun execute(command: Runnable) {
			tasks.addLast(command)
		}
		fun runNext() {
			val task = FutureTask(tasks.removeFirst(), Unit)
			Thread(task, "inbox-test-io").start()
			task.get(10, TimeUnit.SECONDS)
		}
	}

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

	/**
	 * Resets [ReflectiveMatrixHomeserverDiscoveryClient]'s private, process-lifetime
	 * `openedStores` double-open guard. The guard has no in-module test seam since it
	 * lives in a different Gradle module; reflection is the only way to simulate the
	 * fresh-process boundary this in-process test otherwise can't cross.
	 */
	private fun clearOpenedStoresGuard() {
		try {
			val field = ReflectiveMatrixHomeserverDiscoveryClient::class.java.getDeclaredField(
				"openedStores",
			)
			field.isAccessible = true
			(field.get(null) as java.util.HashSet<*>).clear()
		} catch (e: ReflectiveOperationException) {
			// Best-effort: if the guard's storage shape changes, the affected test fails
			// loudly on its own assertions instead of here.
		}
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

	private fun createViewModel(
		vararg registeredConnectors: ReadOnlyConnector,
		matrixAuthSession: MatrixAuthSession = mock(MatrixAuthSession::class.java),
		ioExecutor: Executor = ImmediateExecutor(),
	) = ContactListViewModel(
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
		ioExecutor,
		matrixAuthSession,
	)
}
