package org.briarproject.briar.api.telegram

data class TelegramChat(
	val id: Long,
	val title: String,
	val lastMessageDateSeconds: Int,
)
