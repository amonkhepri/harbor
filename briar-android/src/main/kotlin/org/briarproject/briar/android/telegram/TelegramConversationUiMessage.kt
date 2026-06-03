package org.briarproject.briar.android.telegram

data class TelegramConversationUiMessage(
	val messageId: Long,
	val dateMillis: Long,
	val isOutgoing: Boolean,
	val text: String
)
