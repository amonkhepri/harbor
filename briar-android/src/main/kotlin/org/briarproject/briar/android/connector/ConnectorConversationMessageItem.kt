package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorSource

data class ConnectorConversationMessageItem(
	val connectorSource: ConnectorSource,
	val connectorThreadId: String,
	val connectorMessageId: String,
	val dateMillis: Long,
	val isOutgoing: Boolean,
	val text: String,
) {
	val stableId: String
		get() = "${connectorSource.id}:$connectorThreadId:$connectorMessageId"

	companion object {
		fun from(message: ConnectorMessage): ConnectorConversationMessageItem =
			ConnectorConversationMessageItem(
				message.source,
				message.threadId,
				message.messageId,
				message.dateSeconds * 1000L,
				message.isOutgoing,
				message.text
			)
	}
}
