package org.briarproject.briar.android.telegram

data class TelegramConversationUiMessage(
	val stableId: String,
	val dateMillis: Long,
	val isOutgoing: Boolean,
	val text: String
)
