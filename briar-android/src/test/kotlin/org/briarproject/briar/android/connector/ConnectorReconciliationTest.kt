package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorReconciliationTest {

	private val server = ConnectorConversationMessageItem(
		ConnectorSources.MATRIX,
		"room",
		"event",
		2_000,
		true,
		"hello",
		ConnectorMessageType.TEXT,
	)

	@Test fun sentMediaEchoRemainsUntilMatchingServerRowReplacesItExactlyOnce() {
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
}
