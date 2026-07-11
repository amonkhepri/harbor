package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramConnector
import org.briarproject.briar.api.telegram.TelegramMessage

class StubTelegramConnector(private val messageClient: TelegramTdlibMessageClient) :
	TelegramConnector {
	override fun isEnabled(): Boolean = true

	override fun isAuthorized(): Boolean = messageClient.isAuthorized()

	override fun getRecentChats(limit: Int): List<TelegramChat> = messageClient.getRecentChats(limit)

	override fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage> =
		messageClient.getRecentMessages(chatId, limit)
}
