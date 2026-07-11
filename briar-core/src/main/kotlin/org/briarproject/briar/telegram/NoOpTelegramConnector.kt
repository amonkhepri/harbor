package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramConnector
import org.briarproject.briar.api.telegram.TelegramMessageReadResult
class NoOpTelegramConnector : TelegramConnector {
	override fun isEnabled(): Boolean = false

	override fun isAuthorized(): Boolean = false

	override fun getRecentChats(limit: Int): List<TelegramChat> = emptyList()

	override fun getRecentMessageReadResult(chatId: Long, limit: Int): TelegramMessageReadResult =
		TelegramMessageReadResult.Success(emptyList())
}
