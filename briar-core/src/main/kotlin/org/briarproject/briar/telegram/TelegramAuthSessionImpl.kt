package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramAuthSession
import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthState

class TelegramAuthSessionImpl(
	private val tdlibLoginClient: TelegramTdlibLoginClient,
) : TelegramAuthSession {

	private var currentState = TelegramAuthState.CLOSED

	override fun getCurrentState(): TelegramAuthState = currentState

	override fun getRecoverableErrorDetail(): RecoverableErrorDetail =
		tdlibLoginClient.getRecoverableErrorDetail()

	override fun start() {
		currentState = tdlibLoginClient.start()
	}

	override fun submitIdentifier(identifier: String) {
		currentState = tdlibLoginClient.submitIdentifier(identifier)
	}

	override fun submitCode(code: String) {
		currentState = tdlibLoginClient.submitCode(code)
	}

	override fun submitPassword(password: String) {
		currentState = tdlibLoginClient.submitPassword(password)
	}

	override fun close() {
		currentState = tdlibLoginClient.close()
	}
}

class NoOpTelegramTdlibLoginClient : TelegramTdlibLoginClient {
	override fun start(): TelegramAuthState = TelegramAuthState.CLOSED

	override fun getRecoverableErrorDetail(): RecoverableErrorDetail =
		RecoverableErrorDetail.NONE

	override fun submitIdentifier(identifier: String): TelegramAuthState =
		TelegramAuthState.CLOSED

	override fun submitCode(code: String): TelegramAuthState =
		TelegramAuthState.CLOSED

	override fun submitPassword(password: String): TelegramAuthState =
		TelegramAuthState.CLOSED

	override fun close(): TelegramAuthState = TelegramAuthState.CLOSED
}
