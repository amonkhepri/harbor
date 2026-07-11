package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramConnector
import org.briarproject.briar.api.telegram.TelegramMessageReadResult

class StubTelegramConnector(private val messageClient: TelegramTdlibMessageClient) :
	TelegramConnector {
	override fun isEnabled(): Boolean = true

	override fun isAuthorized(): Boolean = messageClient.isAuthorized()

	override fun getRecentChats(limit: Int): List<TelegramChat> = messageClient.getRecentChats(limit)

	override fun getRecentMessageReadResult(chatId: Long, limit: Int): TelegramMessageReadResult =
		messageClient.getRecentMessageReadResult(chatId, limit)
}
