package org.drinkless.tdlib

class Client private constructor(private val updateHandler: ResultHandler) {
	private var databaseDirectory = ""

	fun interface ResultHandler {
		fun onResult(obj: Any?)
	}

	fun interface ExceptionHandler {
		fun onException(throwable: Throwable)
	}

	init {
		emitAuthorizationState(initialAuthorizationState)
	}

	fun send(request: TdApi.Function, resultHandler: ResultHandler?) {
		sentRequestNames += request.javaClass.simpleName
		when (request) {
			is TdApi.SetTdlibParameters -> {
				val requestedDatabaseDirectory = request.databaseDirectory.orEmpty()
				if (!activateDatabaseDirectory(requestedDatabaseDirectory)) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				databaseDirectory = requestedDatabaseDirectory
				lastDatabaseDirectory = requestedDatabaseDirectory
				lastFilesDirectory = request.filesDirectory.orEmpty()
				lastApiId = request.apiId
				lastApiHash = request.apiHash.orEmpty()
				if (tdlibParametersError) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				resultHandler?.onResult(TdApi.Ok())
				emitAuthorizationState(authorizationStateAfterTdlibParameters)
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
				emitResult(resultHandler, TdApi.Ok(), authenticationCodeResultDelayMs)
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
			is TdApi.GetChats -> {
				val chatIds = chatsById.keys.take(request.limit).toLongArray()
				resultHandler?.onResult(
					TdApi.Chats().also {
						it.totalCount = chatIds.size
						it.chatIds = chatIds
					},
				)
			}
			is TdApi.GetChat -> {
				resultHandler?.onResult(chatsById[request.chatId] ?: TdApi.Error())
			}
			is TdApi.GetChatHistory -> {
				val messages = messagesByChatId[request.chatId]
					.orEmpty()
					.drop(historyStartIndex(request))
					.take(request.limit)
					.take(maxHistoryPageSize)
					.toTypedArray()
				resultHandler?.onResult(
					TdApi.Messages().also {
						it.totalCount = messages.size
						it.messages = messages
					},
				)
			}
			is TdApi.Close -> {
				resultHandler?.onResult(TdApi.Ok())
				emitCloseAuthorizationState()
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

	private fun emitCloseAuthorizationState() {
		closeAuthorizationStatePrelude?.let {
			updateHandler.onResult(TdApi.UpdateAuthorizationState(it))
		}
		val emit = {
			clearActiveDatabaseDirectory(databaseDirectory)
			updateHandler.onResult(
				TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()),
			)
		}
		if (closeAuthorizationUpdateDelayMs <= 0L) {
			emit()
			return
		}
		Thread {
			try {
				Thread.sleep(closeAuthorizationUpdateDelayMs)
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
		private var closeAuthorizationUpdateDelayMs = 0L
		private var phoneNumberResultDelayMs = 0L
		private var authenticationCodeResultDelayMs = 0L
		private var closeAuthorizationStatePrelude: Any? = null
		private var lastPhoneNumber = ""
		private var lastDatabaseDirectory = ""
		private var lastFilesDirectory = ""
		private var lastApiId = 0
		private var lastApiHash = ""
		private var tdlibParametersError = false
		private var initialAuthorizationState: Any =
			TdApi.AuthorizationStateWaitTdlibParameters()
		private var authorizationStateAfterTdlibParameters: Any =
			TdApi.AuthorizationStateWaitPhoneNumber()
		private val chatsById = linkedMapOf<Long, TdApi.Chat>()
		private val messagesByChatId = mutableMapOf<Long, List<TdApi.Message?>>()
		private var maxHistoryPageSize = Int.MAX_VALUE
		private var activeDatabaseDirectory = ""

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
			closeAuthorizationUpdateDelayMs = 0L
			phoneNumberResultDelayMs = 0L
			authenticationCodeResultDelayMs = 0L
			closeAuthorizationStatePrelude = null
			lastPhoneNumber = ""
			lastDatabaseDirectory = ""
			lastFilesDirectory = ""
			lastApiId = 0
			lastApiHash = ""
			tdlibParametersError = false
			initialAuthorizationState = TdApi.AuthorizationStateWaitTdlibParameters()
			authorizationStateAfterTdlibParameters =
				TdApi.AuthorizationStateWaitPhoneNumber()
			chatsById.clear()
			messagesByChatId.clear()
			maxHistoryPageSize = Int.MAX_VALUE
			activeDatabaseDirectory = ""
		}

		@JvmStatic
		fun setAuthorizationUpdateDelayMs(delayMs: Long) {
			authorizationUpdateDelayMs = delayMs
		}

		@JvmStatic
		fun setCloseAuthorizationUpdateDelayMs(delayMs: Long) {
			closeAuthorizationUpdateDelayMs = delayMs
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
		fun setAuthenticationCodeResultDelayMs(delayMs: Long) {
			authenticationCodeResultDelayMs = delayMs
		}

		@JvmStatic
		fun setCloseAuthorizationStatePrelude(authorizationState: Any?) {
			closeAuthorizationStatePrelude = authorizationState
		}

		@JvmStatic
		fun setTdlibParametersError(enabled: Boolean) {
			tdlibParametersError = enabled
		}

		@JvmStatic
		fun setInitialAuthorizationState(authorizationState: Any) {
			initialAuthorizationState = authorizationState
		}

		@JvmStatic
		fun setAuthorizationStateAfterTdlibParameters(authorizationState: Any) {
			authorizationStateAfterTdlibParameters = authorizationState
		}

		@JvmStatic
		fun setChats(vararg chats: TdApi.Chat) {
			chatsById.clear()
			chats.forEach { chatsById[it.id] = it }
		}

		@JvmStatic
		fun setMessages(chatId: Long, vararg messages: TdApi.Message?) {
			messagesByChatId[chatId] = messages.toList()
		}

		@JvmStatic
		fun setMaxHistoryPageSize(limit: Int) {
			maxHistoryPageSize = limit.coerceAtLeast(1)
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

		private fun activateDatabaseDirectory(databaseDirectory: String): Boolean = synchronized(this) {
			if (databaseDirectory.isEmpty()) return@synchronized true
			if (activeDatabaseDirectory.isNotEmpty() &&
				activeDatabaseDirectory != databaseDirectory
			) {
				activeDatabaseDirectory = databaseDirectory
				return@synchronized true
			}
			if (activeDatabaseDirectory == databaseDirectory) return@synchronized false
			activeDatabaseDirectory = databaseDirectory
			true
		}

		private fun clearActiveDatabaseDirectory(databaseDirectory: String) {
			synchronized(this) {
				if (activeDatabaseDirectory == databaseDirectory) {
					activeDatabaseDirectory = ""
				}
			}
		}

		private fun historyStartIndex(request: TdApi.GetChatHistory): Int {
			if (request.fromMessageId == 0L) return 0
			val messages = messagesByChatId[request.chatId].orEmpty()
			val index = messages.indexOfFirst { it?.id == request.fromMessageId }
			if (index == -1) return messages.size
			return (index + request.offset).coerceIn(0, messages.size)
		}
	}
}
