package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramConnector
import org.briarproject.briar.api.telegram.TelegramMessage

class NoOpTelegramConnector : TelegramConnector {
	override fun isEnabled(): Boolean = false

	override fun isAuthorized(): Boolean = false

	override fun getRecentChats(limit: Int): List<TelegramChat> = emptyList()

	override fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage> =
		emptyList()
}
