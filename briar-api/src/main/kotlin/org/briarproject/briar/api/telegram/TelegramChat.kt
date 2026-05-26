package org.briarproject.briar.api.telegram

data class TelegramChat @JvmOverloads constructor(
	val id: Long,
	val title: String,
	val lastMessageDateSeconds: Int,
	val lastMessageText: String = "",
	val lastMessageIsOutgoing: Boolean = false,
)
