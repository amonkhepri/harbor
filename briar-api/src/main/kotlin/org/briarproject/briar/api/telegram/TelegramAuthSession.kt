package org.briarproject.briar.api.telegram

interface TelegramAuthSession {
	enum class RecoverableErrorDetail {
		NONE,
		MISSING_TDLIB,
		MISSING_API_CREDENTIALS,
		INVALID_IDENTIFIER,
		INVALID_CODE,
		INVALID_PASSWORD,
		UNSUPPORTED_AUTH_STEP,
		DEVICE_KEYSTORE_UNAVAILABLE,
		TDLIB_DATABASE_KEY_MISMATCH,
		PERSISTED_SESSION_IDENTITY_UNVERIFIED,
	}

	fun getCurrentState(): TelegramAuthState
	fun getRecoverableErrorDetail(): RecoverableErrorDetail
	fun start()
	fun submitIdentifier(identifier: String)
	fun submitCode(code: String)
	fun submitPassword(password: String)
	fun close()
}
