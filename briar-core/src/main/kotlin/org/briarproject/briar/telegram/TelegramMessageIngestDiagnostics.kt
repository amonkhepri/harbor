package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramConnector
import org.briarproject.briar.api.telegram.TelegramMessage
import org.briarproject.briar.api.telegram.TelegramMessageIngestSnapshot
import org.briarproject.briar.api.telegram.TelegramMessageIngestStatus

class TelegramMessageIngestDiagnostics(private val connector: TelegramConnector) {
	fun readSnapshot(chatLimit: Int, messageLimit: Int): TelegramMessageIngestSnapshot {
		if (!connector.isEnabled()) {
			return TelegramMessageIngestSnapshot(
				status = TelegramMessageIngestStatus.DISABLED,
				recentChatCount = 0,
				sampledMessageCount = 0,
			)
		}
		if (!connector.isAuthorized()) {
			return TelegramMessageIngestSnapshot(
				status = TelegramMessageIngestStatus.AUTHORIZATION_UNAVAILABLE,
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
		if (messageLimit <= 0) {
			return TelegramMessageIngestSnapshot(
				status = TelegramMessageIngestStatus.CHAT_COUNT_ONLY,
				recentChatCount = chats.size,
				sampledMessageCount = 0,
			)
		}
		val budget = messageLimit
		val perChatLimit = (budget / chats.size).coerceAtLeast(1)
		val sampledMessages = mutableListOf<TelegramMessage>()
		for (chat in chats) {
			if (sampledMessages.size >= budget) break
			val chatMessages = connector.getRecentMessages(chat.id, perChatLimit)
			sampledMessages.addAll(chatMessages.take(budget - sampledMessages.size))
		}
		return TelegramMessageIngestSnapshot(
			status = if (sampledMessages.isEmpty()) {
				TelegramMessageIngestStatus.CHAT_COUNT_ONLY
			} else {
				TelegramMessageIngestStatus.MESSAGE_COUNT_AVAILABLE
			},
			recentChatCount = chats.size,
			sampledMessageCount = sampledMessages.size,
		)
	}
}
