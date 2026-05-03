package org.drinkless.tdlib

class Client private constructor(
	private val updateHandler: ResultHandler,
) {

	fun interface ResultHandler {
		fun onResult(obj: Any?)
	}

	fun interface ExceptionHandler {
		fun onException(throwable: Throwable)
	}

	init {
		emitAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters())
	}

	fun send(request: TdApi.Function, resultHandler: ResultHandler?) {
		sentRequestNames += request.javaClass.simpleName
		when (request) {
			is TdApi.SetTdlibParameters -> {
				lastDatabaseDirectory = request.databaseDirectory.orEmpty()
				lastFilesDirectory = request.filesDirectory.orEmpty()
				resultHandler?.onResult(TdApi.Ok())
				emitAuthorizationState(TdApi.AuthorizationStateWaitPhoneNumber())
			}
			is TdApi.SetAuthenticationPhoneNumber -> {
				lastPhoneNumber = request.phoneNumber
				if (request.settings == null || request.phoneNumber.contains("invalid")) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				resultHandler?.onResult(TdApi.Ok())
				emitAuthorizationState(TdApi.AuthorizationStateWaitCode())
			}
			is TdApi.CheckAuthenticationCode -> {
				if (request.code.contains("invalid")) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				resultHandler?.onResult(TdApi.Ok())
				if (request.code.contains("password-required")) {
					emitAuthorizationState(TdApi.AuthorizationStateWaitPassword())
				} else {
					emitAuthorizationState(TdApi.AuthorizationStateReady())
				}
			}
			is TdApi.CheckAuthenticationPassword -> {
				if (request.password.contains("invalid")) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				resultHandler?.onResult(TdApi.Ok())
				emitAuthorizationState(TdApi.AuthorizationStateReady())
			}
			is TdApi.Close -> {
				resultHandler?.onResult(TdApi.Ok())
				emitAuthorizationState(TdApi.AuthorizationStateClosed())
			}
			else -> resultHandler?.onResult(TdApi.Ok())
		}
	}

	private fun emitAuthorizationState(authorizationState: Any) {
		val delayMs = getAuthorizationUpdateDelayMs()
		val emit = {
			updateHandler.onResult(
				TdApi.UpdateAuthorizationState(authorizationState),
			)
		}
		if (delayMs <= 0L) {
			emit()
			return
		}
		Thread {
			try {
				Thread.sleep(delayMs)
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
			}
			emit()
		}.start()
	}

	companion object {
		private val sentRequestNames = mutableListOf<String>()
		private val authorizationUpdateDelaySequenceMs = mutableListOf<Long>()
		private var authorizationUpdateDelayMs = 0L
		private var lastPhoneNumber = ""
		private var lastDatabaseDirectory = ""
		private var lastFilesDirectory = ""

		@JvmStatic
		fun create(
			updateHandler: ResultHandler,
			updatesExceptionHandler: ExceptionHandler?,
			defaultExceptionHandler: ExceptionHandler?,
		): Client = Client(updateHandler)

		@JvmStatic
		fun resetTestState() {
			sentRequestNames.clear()
			authorizationUpdateDelaySequenceMs.clear()
			authorizationUpdateDelayMs = 0L
			lastPhoneNumber = ""
			lastDatabaseDirectory = ""
			lastFilesDirectory = ""
		}

		@JvmStatic
		fun setAuthorizationUpdateDelayMs(delayMs: Long) {
			authorizationUpdateDelayMs = delayMs
		}

		@JvmStatic
		fun setAuthorizationUpdateDelaySequenceMs(vararg delayMs: Long) {
			authorizationUpdateDelaySequenceMs.clear()
			authorizationUpdateDelaySequenceMs += delayMs.toList()
		}

		@JvmStatic
		fun getSentRequestNames(): List<String> = sentRequestNames.toList()

		@JvmStatic
		fun getLastPhoneNumber(): String = lastPhoneNumber

		@JvmStatic
		fun getLastDatabaseDirectory(): String = lastDatabaseDirectory

		@JvmStatic
		fun getLastFilesDirectory(): String = lastFilesDirectory

		private fun getAuthorizationUpdateDelayMs(): Long =
			if (authorizationUpdateDelaySequenceMs.isNotEmpty()) {
				authorizationUpdateDelaySequenceMs.removeAt(0)
			} else {
				authorizationUpdateDelayMs
			}
	}
}
