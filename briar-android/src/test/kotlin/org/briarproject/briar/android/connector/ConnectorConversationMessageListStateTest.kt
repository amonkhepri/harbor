package org.briarproject.briar.android.connector

import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.DISABLED
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.EMPTY
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.LOAD_FAILED
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.briarproject.briar.api.connector.ConnectorSources
import java.io.File

class ConnectorConversationMessageListStateTest {

	@Test
	fun testTransientFailuresKeepPreviousMessages() {
		val previousState = ConnectorConversationMessageListState(
			messages = listOf(messageItem("1")),
			emptyText = "Loading",
			isLoading = true,
		)

		listOf(ACCOUNT_UNAVAILABLE, LOAD_FAILED).forEach { availabilityState ->
			val state = ConnectorConversationMessageListState()
				.withAvailabilityState(availabilityState, previousState, "Failed")

			assertEquals(listOf(messageItem("1")), state.messages)
			assertEquals("Failed", state.emptyText)
			assertEquals("Failed", state.visibleStatusText)
			assertFalse(state.isLoading)
		}
	}

	@Test
	fun testDisabledStateClearsHistoryAndStrandedLocalEchoRowsAlike() {
		val previousState = ConnectorConversationMessageListState(messages = listOf(messageItem("1")))
			.beginTextSend(CONNECTOR_SOURCE, "thread-1", "hello", 2_000)

		val state = ConnectorConversationMessageListState()
			.withAvailabilityState(DISABLED, previousState, "Disabled")

		assertEquals(emptyList<ConnectorConversationMessageItem>(), state.messages)
		// The pending send is still tracked internally so it can still be reconciled once the
		// connector is re-enabled and the thread reloads, even though it is not shown while
		// disabled.
		assertEquals(1, state.localEchoes.size)
	}

	@Test
	fun testSendCompletionWhileDisabledKeepsEchoHiddenUntilReload() {
		for (sent in listOf(false, true)) {
			val sending = ConnectorConversationMessageListState(messages = listOf(messageItem("1")))
				.beginTextSend(CONNECTOR_SOURCE, "thread-1", "hello", 2_000)
			val id = sending.localEchoes.single().item.stableId
			val disabled = ConnectorConversationMessageListState()
				.withAvailabilityState(DISABLED, sending, "Disabled")
			val completed = disabled.completeSend(id, sent)

			assertEquals(emptyList<ConnectorConversationMessageItem>(), completed.messages)
			assertEquals("Disabled", completed.emptyText)
			assertNull(completed.visibleStatusText)
			val echo = completed.localEchoes.single()
			assertEquals(true, echo.completed)
			assertEquals(id, echo.item.stableId)
			assertEquals(
				if (sent) ConnectorLocalEchoStatus.SENT else ConnectorLocalEchoStatus.FAILED,
				echo.item.localEchoStatus,
			)

			// An enabled reload restores the retained echo, and successful sends can reconcile.
			assertEquals(listOf(echo.item), completed.reconcile(emptyList()).messages)
			val server = echo.item.copy(connectorMessageId = "server", localEchoStatus = null)
			val reloaded = completed.reconcile(listOf(server))
			assertEquals(if (sent) listOf(server) else listOf(server, echo.item), reloaded.messages)
			assertEquals(if (sent) 0 else 1, reloaded.localEchoes.size)
		}
	}

	@Test
	fun testSuccessfulEmptyLoadClearsPreviousMessages() {
		val previousState = ConnectorConversationMessageListState(
			messages = listOf(messageItem("1")),
			emptyText = "Loading",
			isLoading = true,
		)

		val state = ConnectorConversationMessageListState()
			.withAvailabilityState(EMPTY, previousState, "No messages")

		assertEquals(emptyList<ConnectorConversationMessageItem>(), state.messages)
		assertEquals("No messages", state.emptyText)
		assertNull(state.visibleStatusText)
		assertFalse(state.isLoading)
	}

	private fun messageItem(messageId: String): ConnectorConversationMessageItem =
		ConnectorConversationMessageItem(
			connectorSource = CONNECTOR_SOURCE,
			connectorThreadId = "thread-1",
			connectorMessageId = messageId,
			dateMillis = 1_700_000_000_000L,
			isOutgoing = false,
			text = "body",
			type = ConnectorMessageType.TEXT,
		)

	@Test fun sentMediaEchoRemainsUntilMatchingServerRowReplacesItExactlyOnce() {
		val server = ConnectorConversationMessageItem(
			ConnectorSources.MATRIX,
			"room",
			"event",
			2_000,
			true,
			"hello",
			ConnectorMessageType.TEXT,
		)
		val unrelated = server.copy(connectorMessageId = "unrelated")
		for (type in listOf(ConnectorMessageType.PHOTO, ConnectorMessageType.FILE)) {
			var state = ConnectorConversationMessageListState(messages = listOf(unrelated))
				.beginMediaSend(ConnectorSources.MATRIX, "room", type, 2_000)

			val id = state.messages.last().stableId
			state = state.completeSend(id, true)

			state = state.reconcile(listOf(unrelated))
			assertEquals(2, state.messages.size)
			assertEquals(ConnectorLocalEchoStatus.SENT, state.messages.last().localEchoStatus)

			val confirmed = server.copy(connectorMessageId = "confirmed", type = type, text = "")
			state = state.reconcile(listOf(unrelated, confirmed))
			assertEquals(listOf(unrelated, confirmed), state.messages)
			assertTrue(state.localEchoes.isEmpty())

			state = state.reconcile(listOf(unrelated, confirmed))
			assertEquals(listOf(unrelated, confirmed), state.messages)
			assertTrue(state.localEchoes.isEmpty())
		}
	}

	@Test fun sentTextEchoReconcilesWithClockSkewWithoutMatchingKnownRows() {
		val base = messageItem("base")
		val server = messageItem("server").copy(isOutgoing = true)
		val historical = server.copy(connectorMessageId = "h", dateMillis = 0)
		for (known in listOf(listOf(base), listOf(base, server))) {
			val sending = ConnectorConversationMessageListState(messages = known)
				.beginTextSend(CONNECTOR_SOURCE, "thread-1", server.text, server.dateMillis + 5_000)
			val completed = sending.completeSend(sending.localEchoes.single().item.stableId, true)
			val all = listOf(historical, base, server)
			val state = completed.reconcile(all)
			val echo = completed.localEchoes.single().item
			val expected = if (server in known) all + echo else all
			assertEquals(expected, state.messages)
			assertEquals(if (server in known) completed.localEchoes else emptyList(), state.localEchoes)
		}
	}

	@Test fun testMarkMessageDeletedReplacesOnlyMatchingStableId() {
		val item = messageItem("1").copy(isOutgoing = true)
		val otherThread = item.copy(connectorThreadId = "thread-2")
		val state = ConnectorConversationMessageListState(
			messages = listOf(otherThread, item.copy(text = "refreshed")),
		)

		val deleted = markMessageDeleted(state, item)

		assertEquals(
			listOf(otherThread, item.copy(type = ConnectorMessageType.DELETED)),
			deleted.messages,
		)
	}

	@Test fun reconcilePreservesDownloadStateAndDownloadedFileAcrossRefreshes() {
		val downloadedFile = File("/tmp/test.pdf")
		val fileItem = messageItem("file-1").copy(
			type = ConnectorMessageType.FILE,
			downloadState = ConnectorDownloadState.DOWNLOADING,
		)
		val downloadedItem = messageItem("file-2").copy(
			type = ConnectorMessageType.FILE,
			downloadState = ConnectorDownloadState.DOWNLOADED,
			downloadedFile = downloadedFile,
		)
		val failedItem = messageItem("file-3").copy(
			type = ConnectorMessageType.FILE,
			downloadState = ConnectorDownloadState.FAILED,
		)
		val state = ConnectorConversationMessageListState(
			messages = listOf(fileItem, downloadedItem, failedItem),
		)

		val freshServer = listOf(
			messageItem("file-1").copy(type = ConnectorMessageType.FILE),
			messageItem("file-2").copy(type = ConnectorMessageType.FILE),
			messageItem("file-3").copy(type = ConnectorMessageType.FILE),
		)

		val reconciled = state.reconcile(freshServer)

		assertEquals(ConnectorDownloadState.DOWNLOADING, reconciled.messages[0].downloadState)
		assertEquals(null, reconciled.messages[0].downloadedFile)

		assertEquals(ConnectorDownloadState.DOWNLOADED, reconciled.messages[1].downloadState)
		assertEquals(downloadedFile, reconciled.messages[1].downloadedFile)

		assertEquals(ConnectorDownloadState.FAILED, reconciled.messages[2].downloadState)
		assertEquals(null, reconciled.messages[2].downloadedFile)
	}

	companion object {
		private val CONNECTOR_SOURCE = ConnectorSource("sample", "Sample")
	}
}
