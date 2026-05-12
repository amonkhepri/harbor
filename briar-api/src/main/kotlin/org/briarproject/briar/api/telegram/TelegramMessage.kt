package org.briarproject.briar.api.telegram

data class TelegramMessage(
	val chatId: Long,
	val messageId: Long,
	val dateSeconds: Int,
	val isOutgoing: Boolean,
	val text: String,
)
