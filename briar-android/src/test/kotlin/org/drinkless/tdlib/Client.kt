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
				lastApiId = request.apiId
				lastApiHash = request.apiHash.orEmpty()
				if (tdlibParametersError) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				resultHandler?.onResult(TdApi.Ok())
				emitAuthorizationState(TdApi.AuthorizationStateWaitPhoneNumber())
			}
			is TdApi.SetAuthenticationPhoneNumber -> {
				lastPhoneNumber = request.phoneNumber
				if (request.settings == null || request.phoneNumber.contains("invalid")) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				emitResult(resultHandler, TdApi.Ok(), phoneNumberResultDelayMs)
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

	private fun emitResult(resultHandler: ResultHandler?, result: Any, delayMs: Long) {
		if (resultHandler == null) return
		if (delayMs <= 0L) {
			resultHandler.onResult(result)
			return
		}
		Thread {
			try {
				Thread.sleep(delayMs)
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
			}
			resultHandler.onResult(result)
		}.start()
	}

	companion object {
		private val sentRequestNames = mutableListOf<String>()
		private val authorizationUpdateDelaySequenceMs = mutableListOf<Long>()
		private var authorizationUpdateDelayMs = 0L
		private var phoneNumberResultDelayMs = 0L
		private var lastPhoneNumber = ""
		private var lastDatabaseDirectory = ""
		private var lastFilesDirectory = ""
		private var lastApiId = 0
		private var lastApiHash = ""
		private var tdlibParametersError = false

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
			phoneNumberResultDelayMs = 0L
			lastPhoneNumber = ""
			lastDatabaseDirectory = ""
			lastFilesDirectory = ""
			lastApiId = 0
			lastApiHash = ""
			tdlibParametersError = false
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
		fun setPhoneNumberResultDelayMs(delayMs: Long) {
			phoneNumberResultDelayMs = delayMs
		}

		@JvmStatic
		fun setTdlibParametersError(enabled: Boolean) {
			tdlibParametersError = enabled
		}

		@JvmStatic
		fun getSentRequestNames(): List<String> = sentRequestNames.toList()

		@JvmStatic
		fun getLastPhoneNumber(): String = lastPhoneNumber

		@JvmStatic
		fun getLastDatabaseDirectory(): String = lastDatabaseDirectory

		@JvmStatic
		fun getLastFilesDirectory(): String = lastFilesDirectory

		@JvmStatic
		fun getLastApiId(): Int = lastApiId

		@JvmStatic
		fun getLastApiHash(): String = lastApiHash

		private fun getAuthorizationUpdateDelayMs(): Long =
			if (authorizationUpdateDelaySequenceMs.isNotEmpty()) {
				authorizationUpdateDelaySequenceMs.removeAt(0)
			} else {
				authorizationUpdateDelayMs
			}
	}
}
