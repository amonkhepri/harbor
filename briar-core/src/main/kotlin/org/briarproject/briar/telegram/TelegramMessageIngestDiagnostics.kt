package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramConnector
import org.briarproject.briar.api.telegram.TelegramMessageIngestSnapshot
import org.briarproject.briar.api.telegram.TelegramMessageIngestStatus

class TelegramMessageIngestDiagnostics(
	private val connector: TelegramConnector,
) {
	fun readSnapshot(
		chatLimit: Int,
		messageLimit: Int,
	): TelegramMessageIngestSnapshot {
		if (!connector.isEnabled()) {
			return TelegramMessageIngestSnapshot(
					status = TelegramMessageIngestStatus.DISABLED,
					recentChatCount = 0,
					sampledMessageCount = 0,
			)
		}
		val chats = connector.getRecentChats(chatLimit)
		if (chats.isEmpty()) {
			return TelegramMessageIngestSnapshot(
					status = TelegramMessageIngestStatus.NO_CONTENT,
					recentChatCount = 0,
					sampledMessageCount = 0,
			)
		}
		val messages = connector.getRecentMessages(chats.first().id, messageLimit)
		return TelegramMessageIngestSnapshot(
				status = if (messages.isEmpty()) {
					TelegramMessageIngestStatus.CHAT_COUNT_ONLY
				} else {
					TelegramMessageIngestStatus.MESSAGE_COUNT_AVAILABLE
				},
				recentChatCount = chats.size,
				sampledMessageCount = messages.size,
		)
	}
}
