package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorMessageType

data class ConnectorConversationMessageItem(
	val connectorSource: ConnectorSource,
	val connectorThreadId: String,
	val connectorMessageId: String,
	val dateMillis: Long,
	val isOutgoing: Boolean,
	val text: String,
	val type: ConnectorMessageType,
	val isEdited: Boolean = false,
) {
	val stableId: String
		get() = "${connectorSource.id}:$connectorThreadId:$connectorMessageId"

	companion object {
		private val byDateAscending = compareBy<ConnectorMessage> {
			it.dateSeconds
		}.thenBy {
			it.sourceMessageOrder
		}.thenBy {
			it.messageId
		}

		fun fromMessages(messages: List<ConnectorMessage>): List<ConnectorConversationMessageItem> =
			messages.sortedWith(byDateAscending)
				.filter {
					it.type == ConnectorMessageType.PHOTO ||
						it.type == ConnectorMessageType.FILE ||
						it.text.trim().isNotEmpty()
				}
				.map { from(it) }

		fun from(message: ConnectorMessage): ConnectorConversationMessageItem =
			ConnectorConversationMessageItem(
				message.source,
				message.threadId,
				message.messageId,
				message.dateSeconds * 1000L,
				message.isOutgoing,
				message.text,
				message.type,
				message.isEdited,
			)
	}
}
