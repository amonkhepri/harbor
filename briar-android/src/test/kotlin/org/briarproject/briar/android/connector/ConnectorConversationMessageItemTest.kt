package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorAttachmentMetadata
import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReactionSummary
import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectorConversationMessageItemTest {

	@Test
	fun testFromMessagesKeepsPlaceholdersButSkipsBlankTextAndSortsByConnectorOrder() {
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage("30", 30, true, "new", 30L),
				connectorMessage("2", 20, false, "", 2L),
				connectorMessage("3", 25, false, "", 3L, ConnectorMessageType.PHOTO),
				connectorMessage("4", 40, false, " \t\n", 4L),
				connectorMessage("1", 10, false, "old", 1L),
				connectorMessage("5", 26, false, "", 5L, ConnectorMessageType.FILE),
				connectorMessage("20", 30, false, "same time", 20L),
				connectorMessage("6", 27, false, "", 6L, ConnectorMessageType.DELETED),
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
				connectorMessageItem("6", false, "", 27000L, ConnectorMessageType.DELETED),
				connectorMessageItem("20", false, "same time", 30000L),
				connectorMessageItem("30", true, "new", 30000L),
			),
			items,
		)
	}

	@Test
	fun testFromMessagesPreservesInputOrderWhenDateAndSourceOrderTie() {
		// Mirrors Matrix: `sourceMessageOrder` is the same second-truncated timestamp as
		// `dateSeconds` (MatrixRoomConnector.kt), so same-second messages tie on both sort
		// keys. `messageId` ("z-event" sorts after "a-event") must not be used to break that
		// tie; the connector client's own oldest-first order should win instead.
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage("z-event", 30, false, "first", 30L),
				connectorMessage("a-event", 30, false, "second", 30L),
			),
		)

		assertEquals(
			listOf(
				connectorMessageItem("z-event", false, "first", 30000L),
				connectorMessageItem("a-event", false, "second", 30000L),
			),
			items,
		)
	}

	@Test
	fun testFromMessagesDeduplicatesOverlappingMessagesPreservingLatestState() {
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage("1", 10, false, "old 1", 1L),
				connectorMessage("2", 20, false, "message 2", 2L),
				connectorMessage("1", 10, false, "updated 1", 1L, isEdited = true),
				connectorMessage("3", 30, false, "message 3", 3L),
				connectorMessage("2", 20, false, "message 2", 2L),
			),
		)

		assertEquals(
			listOf(
				connectorMessageItem("1", false, "updated 1", 10000L).copy(isEdited = true),
				connectorMessageItem("2", false, "message 2", 20000L),
				connectorMessageItem("3", false, "message 3", 30000L),
			),
			items,
		)
	}

	@Test
	fun testFromMessagesPrefersDeletedStateOverEditedTextWhenDeletedIsSecond() {
		val attachment = ConnectorAttachmentMetadata("file.txt", "text/plain", 123L)
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"edited text",
					1L,
					isEdited = true,
					attachment = attachment,
				),
				connectorMessage(
					"1",
					10,
					false,
					"",
					1L,
					type = ConnectorMessageType.DELETED,
					isEdited = false,
				),
			),
		)

		assertEquals(1, items.size)
		val item = items.single()
		assertEquals(ConnectorMessageType.DELETED, item.type)
		assertEquals("", item.text)
		assertFalse(item.isEdited)
		assertNull(item.attachment)
	}

	@Test
	fun testFromMessagesPrefersDeletedStateOverEditedTextWhenDeletedIsFirst() {
		val attachment = ConnectorAttachmentMetadata("file.txt", "text/plain", 123L)
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"",
					1L,
					type = ConnectorMessageType.DELETED,
					isEdited = false,
				),
				connectorMessage(
					"1",
					10,
					false,
					"edited text",
					1L,
					isEdited = true,
					attachment = attachment,
				),
			),
		)

		assertEquals(1, items.size)
		val item = items.single()
		assertEquals(ConnectorMessageType.DELETED, item.type)
		assertEquals("", item.text)
		assertFalse(item.isEdited)
		assertNull(item.attachment)
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

	@Test
	fun testFromMessagesPreservesReactionSummaries() {
		val reactions = listOf(ConnectorReactionSummary("👍", 2))
		val item = ConnectorConversationMessageItem.fromMessages(
			listOf(connectorMessage("1", 10, false, "reacted", 1L, reactions = reactions)),
		).single()

		assertEquals(reactions, item.reactions)
	}

	@Test
	fun testFromMessagesClearsReactionsWhenLaterDuplicateHasEmptyReactions() {
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"hello",
					1L,
					reactions = listOf(ConnectorReactionSummary("👍", 1)),
				),
				connectorMessage(
					"1",
					10,
					false,
					"hello",
					1L,
					reactions = emptyList(),
				),
			),
		)

		assertEquals(1, items.size)
		val item = items.single()
		assertEquals(emptyList<ConnectorReactionSummary>(), item.reactions)
	}

	@Test
	fun testFromMessagesUpdatesReactionsWhenLaterDuplicateHasUpdatedReactions() {
		val items = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"hello",
					1L,
					reactions = listOf(ConnectorReactionSummary("👍", 1)),
				),
				connectorMessage(
					"1",
					10,
					false,
					"hello",
					1L,
					reactions = listOf(ConnectorReactionSummary("❤️", 2)),
				),
			),
		)

		assertEquals(1, items.size)
		val item = items.single()
		assertEquals(listOf(ConnectorReactionSummary("❤️", 2)), item.reactions)
	}

	@Test
	fun testFromMessagesPreservesEditedEmptyReactionsAgainstStaleDuplicateWithReactions() {
		// Edited with empty reactions followed by stale unedited with reactions
		val items1 = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"edited text",
					1L,
					isEdited = true,
					reactions = emptyList(),
				),
				connectorMessage(
					"1",
					10,
					false,
					"stale original",
					1L,
					isEdited = false,
					reactions = listOf(ConnectorReactionSummary("👍", 1)),
				),
			),
		)

		assertEquals(1, items1.size)
		assertEquals(emptyList<ConnectorReactionSummary>(), items1.single().reactions)
		assertEquals("edited text", items1.single().text)

		// Stale unedited with reactions followed by edited with empty reactions
		val items2 = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"stale original",
					1L,
					isEdited = false,
					reactions = listOf(ConnectorReactionSummary("👍", 1)),
				),
				connectorMessage(
					"1",
					10,
					false,
					"edited text",
					1L,
					isEdited = true,
					reactions = emptyList(),
				),
			),
		)

		assertEquals(1, items2.size)
		assertEquals(emptyList<ConnectorReactionSummary>(), items2.single().reactions)
		assertEquals("edited text", items2.single().text)
	}

	@Test
	fun testFromMessagesClearsReactionsWhenMessageIsDeletedInEitherOrder() {
		// Reacted then deleted
		val items1 = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"msg",
					1L,
					reactions = listOf(ConnectorReactionSummary("👍", 1)),
				),
				connectorMessage(
					"1",
					10,
					false,
					"",
					1L,
					type = ConnectorMessageType.DELETED,
				),
			),
		)

		assertEquals(1, items1.size)
		assertEquals(emptyList<ConnectorReactionSummary>(), items1.single().reactions)
		assertEquals(ConnectorMessageType.DELETED, items1.single().type)

		// Deleted then reacted
		val items2 = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"",
					1L,
					type = ConnectorMessageType.DELETED,
				),
				connectorMessage(
					"1",
					10,
					false,
					"msg",
					1L,
					reactions = listOf(ConnectorReactionSummary("👍", 1)),
				),
			),
		)

		assertEquals(1, items2.size)
		assertEquals(emptyList<ConnectorReactionSummary>(), items2.single().reactions)
		assertEquals(ConnectorMessageType.DELETED, items2.single().type)
	}

	@Test
	fun testFromMessagesPreservesAttachmentMetadata() {
		val attachment = ConnectorAttachmentMetadata(
			fileName = "sample.pdf",
			mimeType = "application/pdf",
			size = 1000L,
		)
		val item = ConnectorConversationMessageItem.fromMessages(
			listOf(
				connectorMessage(
					"1",
					10,
					false,
					"",
					1L,
					type = ConnectorMessageType.FILE,
					attachment = attachment,
				),
			),
		).single()

		assertEquals(attachment, item.attachment)
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
		reactions: List<ConnectorReactionSummary> = emptyList(),
		attachment: ConnectorAttachmentMetadata? = null,
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
		reactions = reactions,
		attachment = attachment,
	)

	companion object {
		private val CONNECTOR_SOURCE = ConnectorSource("sample", "Sample")
	}
}
