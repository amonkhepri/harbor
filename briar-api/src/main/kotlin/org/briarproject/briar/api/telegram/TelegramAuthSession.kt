package org.briarproject.briar.api.telegram

interface TelegramAuthSession {
	data class Snapshot @JvmOverloads constructor(
		val authState: TelegramAuthState,
		val errorDetail: RecoverableErrorDetail,
		val qrAuthorizationLink: String? = null,
	) {
		override fun toString(): String =
			"Snapshot(authState=$authState, errorDetail=$errorDetail, hasQrAuthorizationLink=${qrAuthorizationLink != null})"
	}

	enum class RecoverableErrorDetail {
		NONE,
		MISSING_TDLIB,
		MISSING_API_CREDENTIALS,
		INVALID_IDENTIFIER,
		INVALID_CODE,
		CODE_RESEND_FAILED,
		INVALID_PASSWORD,
		UNSUPPORTED_AUTH_STEP,
		DEVICE_KEYSTORE_UNAVAILABLE,
		TDLIB_DATABASE_KEY_MISMATCH,
		PERSISTED_SESSION_IDENTITY_UNVERIFIED,
	}

	fun getSnapshot(): Snapshot
	fun start()
	fun submitIdentifier(identifier: String)
	fun resendCode()
	fun submitCode(code: String)
	fun submitPassword(password: String)
	fun requestQrCodeAuthentication()
	fun awaitQrAuthorizationUpdate()
	fun close()
}
