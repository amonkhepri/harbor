package org.briarproject.briar.api.telegram

sealed class TelegramMessageReadResult {
	data class Success(val messages: List<TelegramMessage>) : TelegramMessageReadResult()

	object LoadFailed : TelegramMessageReadResult()
}
