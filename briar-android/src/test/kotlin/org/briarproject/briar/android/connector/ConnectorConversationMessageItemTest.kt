package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorConversationMessageItemTest {

	@Test
	fun testFromMessagesKeepsPhotoAndFileButSkipsBlankTextAndSortsByConnectorOrder() {
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage("30", 30, true, "new", 30L),
				connectorMessage("2", 20, false, "", 2L),
				connectorMessage("3", 25, false, "", 3L, ConnectorMessageType.PHOTO),
				connectorMessage("4", 40, false, " \t\n", 4L),
				connectorMessage("1", 10, false, "old", 1L),
				connectorMessage("5", 26, false, "", 5L, ConnectorMessageType.FILE),
				connectorMessage("20", 30, false, "same time", 20L),
			),
		)

		assertEquals(
			listOf(
				connectorMessageItem("1", false, "old", 10000L),
				connectorMessageItem(
					"3",
					false,
					"",
					25000L,
					ConnectorMessageType.PHOTO,
				),
				connectorMessageItem(
					"5",
					false,
					"",
					26000L,
					ConnectorMessageType.FILE,
				),
				connectorMessageItem("20", false, "same time", 30000L),
				connectorMessageItem("30", true, "new", 30000L),
			),
			items,
		)
	}

	@Test
	fun testFromMessagesPreservesEditedState() {
		val item = ConnectorConversationMessageItem.fromMessages(
			listOf(connectorMessage("1", 10, false, "edited", 1L, isEdited = true)),
		).single()

		assertEquals(true, item.isEdited)
	}

	@Test
	fun testFromMessagesPreservesReplyState() {
		val item = ConnectorConversationMessageItem.fromMessages(
			listOf(connectorMessage("1", 10, false, "reply", 1L, isReply = true)),
		).single()

		assertEquals(true, item.isReply)
	}

	private fun connectorMessageItem(
		messageId: String,
		isOutgoing: Boolean,
		text: String,
		dateMillis: Long,
		type: ConnectorMessageType = ConnectorMessageType.TEXT,
	): ConnectorConversationMessageItem = ConnectorConversationMessageItem(
		connectorSource = CONNECTOR_SOURCE,
		connectorThreadId = "thread-1",
		connectorMessageId = messageId,
		dateMillis = dateMillis,
		isOutgoing = isOutgoing,
		text = text,
		type = type,
	)

	private fun connectorMessage(
		messageId: String,
		dateSeconds: Int,
		isOutgoing: Boolean,
		text: String,
		sourceMessageOrder: Long,
		type: ConnectorMessageType = ConnectorMessageType.TEXT,
		isEdited: Boolean = false,
		isReply: Boolean = false,
	): ConnectorMessage = ConnectorMessage(
		source = CONNECTOR_SOURCE,
		threadId = "thread-1",
		messageId = messageId,
		dateSeconds = dateSeconds,
		isOutgoing = isOutgoing,
		text = text,
		sourceMessageOrder = sourceMessageOrder,
		type = type,
		isEdited = isEdited,
		isReply = isReply,
	)

	companion object {
		private val CONNECTOR_SOURCE = ConnectorSource("sample", "Sample")
	}
}
