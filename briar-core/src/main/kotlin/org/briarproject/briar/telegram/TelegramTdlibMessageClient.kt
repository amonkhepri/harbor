package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramMessage

interface TelegramTdlibMessageClient {
	fun getRecentChats(limit: Int): List<TelegramChat>
	fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage>
}

class NoOpTelegramTdlibMessageClient : TelegramTdlibMessageClient {
	override fun getRecentChats(limit: Int): List<TelegramChat> = emptyList()

	override fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage> =
		emptyList()
}
