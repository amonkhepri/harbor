package org.briarproject.briar.api.telegram

data class TelegramMessageIngestSnapshot(
	val status: TelegramMessageIngestStatus,
	val recentChatCount: Int,
	val sampledMessageCount: Int,
)

enum class TelegramMessageIngestStatus {
	DISABLED,
	NO_CONTENT,
	CHAT_COUNT_ONLY,
	MESSAGE_COUNT_AVAILABLE,
}
