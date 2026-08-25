package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReactionSummary
import org.briarproject.briar.api.connector.ConnectorSource

data class ConnectorConversationMessageItem(
	val connectorSource: ConnectorSource,
	val connectorThreadId: String,
	val connectorMessageId: String,
	val dateMillis: Long,
	val isOutgoing: Boolean,
	val text: String,
	val type: ConnectorMessageType,
	val isEdited: Boolean = false,
	val isReply: Boolean = false,
	val reactions: List<ConnectorReactionSummary> = emptyList(),
) {
	val stableId: String
		get() = "${connectorSource.id}:$connectorThreadId:$connectorMessageId"

	companion object {
		// No third `messageId` tiebreak: `sortedWith` is stable, and connector clients already
		// return messages in true chronological (oldest-first) order, so any tie on
		// (dateSeconds, sourceMessageOrder) is best resolved by preserving input order rather
		// than by comparing opaque message ids as strings. That matters for Matrix, whose
		// `sourceMessageOrder` (MatrixRoomConnector.kt) is the same second-truncated origin
		// server timestamp as `dateSeconds`, so same-second messages tie on both keys; a
		// `messageId` string tiebreak would then sort by the Matrix event id's opaque hash
		// instead of the server's true timeline order, silently reordering same-second
		// messages on screen. Telegram is unaffected: its `sourceMessageOrder` is set to the
		// same raw id used for `messageId` (ReflectiveTelegramTdlibMessageClient.kt), so a tie
		// on `sourceMessageOrder` already implies a tie on `messageId` there.
		private val byDateAscending = compareBy<ConnectorMessage> {
			it.dateSeconds
		}.thenBy {
			it.sourceMessageOrder
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
				message.isReply,
				message.reactions,
			)
	}
}
