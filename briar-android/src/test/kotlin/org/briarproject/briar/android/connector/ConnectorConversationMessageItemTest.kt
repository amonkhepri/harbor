package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorConversationMessageItemTest {

	@Test
	fun testFromMessagesSkipsBlankTextAndSortsByConnectorOrder() {
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage("30", 30, true, "new", 30L),
				connectorMessage("2", 20, false, "", 2L),
				connectorMessage("4", 40, false, " \t\n", 4L),
				connectorMessage("1", 10, false, "old", 1L),
				connectorMessage("20", 30, false, "same time", 20L),
			),
		)

		assertEquals(
			listOf(
				connectorMessageItem("1", false, "old", 10000L),
				connectorMessageItem("20", false, "same time", 30000L),
				connectorMessageItem("30", true, "new", 30000L),
			),
			items,
		)
	}

	private fun connectorMessageItem(
		messageId: String,
		isOutgoing: Boolean,
		text: String,
		dateMillis: Long,
	): ConnectorConversationMessageItem = ConnectorConversationMessageItem(
		connectorSource = CONNECTOR_SOURCE,
		connectorThreadId = "thread-1",
		connectorMessageId = messageId,
		dateMillis = dateMillis,
		isOutgoing = isOutgoing,
		text = text,
	)

	private fun connectorMessage(
		messageId: String,
		dateSeconds: Int,
		isOutgoing: Boolean,
		text: String,
		sourceMessageOrder: Long,
	): ConnectorMessage = ConnectorMessage(
		source = CONNECTOR_SOURCE,
		threadId = "thread-1",
		messageId = messageId,
		dateSeconds = dateSeconds,
		isOutgoing = isOutgoing,
		text = text,
		sourceMessageOrder = sourceMessageOrder,
	)

	companion object {
		private val CONNECTOR_SOURCE = ConnectorSource("sample", "Sample")
	}
}
