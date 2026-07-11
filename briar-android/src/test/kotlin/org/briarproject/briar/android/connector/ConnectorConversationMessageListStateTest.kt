package org.briarproject.briar.android.connector

import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.EMPTY
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.LOAD_FAILED
import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

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
		)

	companion object {
		private val CONNECTOR_SOURCE = ConnectorSource("sample", "Sample")
	}
}
