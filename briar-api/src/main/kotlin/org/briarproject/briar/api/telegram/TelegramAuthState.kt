package org.briarproject.briar.api.telegram

enum class TelegramAuthState {
	IDENTIFIER_ENTRY,
	CODE_ENTRY,
	PASSWORD_ENTRY,
	QR_WAITING,
	READY,
	CLOSED,
	RECOVERABLE_ERROR,
}
