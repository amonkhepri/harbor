package org.briarproject.briar.api.telegram

interface TelegramConnector {
	fun isEnabled(): Boolean
	fun getRecentChats(limit: Int): List<TelegramChat>
	fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage>
}
