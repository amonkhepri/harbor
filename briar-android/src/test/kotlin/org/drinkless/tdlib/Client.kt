package org.drinkless.tdlib

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Client private constructor(private val updateHandler: ResultHandler) {
	private var databaseDirectory = ""
	private var loadedChatCount = 0

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
		recordSentRequest(request.javaClass.simpleName)
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
				lastDatabaseEncryptionKey = request.databaseEncryptionKey?.copyOf() ?: ByteArray(0)
				lastApiId = request.apiId
				lastApiHash = request.apiHash.orEmpty()
				if (rejectSetTdlibParameters) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				setTdlibParametersAcceptedLatch?.countDown()
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
				emitAuthorizationState(authorizationStateAfterCode(request.code))
			}
			is TdApi.CheckAuthenticationPassword -> {
				if (request.password.contains("invalid")) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				resultHandler?.onResult(TdApi.Ok())
				emitAuthorizationState(TdApi.AuthorizationStateReady())
			}
			is TdApi.LoadChats -> {
				if (loadedChatCount >= chatsById.size) {
					resultHandler?.onResult(TdApi.Error())
					return
				}
				loadedChatCount += chatLoadCount(request.limit)
				resultHandler?.onResult(TdApi.Ok())
			}
			is TdApi.GetChats -> {
				val chatIds = chatsById.keys
					.take(minOf(request.limit.coerceAtLeast(0), loadedChatCount))
					.toLongArray()
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
		emitAfter(getAuthorizationUpdateDelayMs()) {
			updateHandler.onResult(
				TdApi.UpdateAuthorizationState(authorizationState),
			)
		}
	}

	private fun emitCloseAuthorizationState() {
		emitAfter(closeAuthorizationUpdateDelayMs) {
			clearActiveDatabaseDirectory(databaseDirectory)
			updateHandler.onResult(
				TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()),
			)
		}
	}

	private fun emitResult(resultHandler: ResultHandler?, result: Any, delayMs: Long) {
		if (resultHandler == null) return
		emitAfter(delayMs) {
			resultHandler.onResult(result)
		}
	}

	private fun emitAfter(delayMs: Long, emit: () -> Unit) {
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

	private fun chatLoadCount(limit: Int): Int {
		val remaining = chatsById.size - loadedChatCount
		if (remaining <= 0 || limit <= 0) return 0
		return minOf(remaining, limit, maxChatLoadPageSize)
	}

	companion object {
		private val stateLock = Any()
		private val sentRequestNames = mutableListOf<String>()
		private val authorizationUpdateDelaySequenceMs = mutableListOf<Long>()
		private var authorizationUpdateDelayMs = 0L
		private var closeAuthorizationUpdateDelayMs = 0L
		private var phoneNumberResultDelayMs = 0L
		private var authenticationCodeResultDelayMs = 0L
		private var lastPhoneNumber = ""
		private var lastDatabaseDirectory = ""
		private var lastFilesDirectory = ""
		private var lastDatabaseEncryptionKey = ByteArray(0)
		private var lastApiId = 0
		private var lastApiHash = ""
		private var initialAuthorizationState: Any =
			TdApi.AuthorizationStateWaitTdlibParameters()
		private var authorizationStateAfterTdlibParameters: Any =
			TdApi.AuthorizationStateWaitPhoneNumber()
		private val chatsById = linkedMapOf<Long, TdApi.Chat>()
		private val messagesByChatId = mutableMapOf<Long, List<TdApi.Message?>>()
		private var maxChatLoadPageSize = Int.MAX_VALUE
		private var maxHistoryPageSize = Int.MAX_VALUE
		private var activeDatabaseDirectory = ""
		private var setTdlibParametersAcceptedLatch: CountDownLatch? = null
		private var rejectSetTdlibParameters = false

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
			lastPhoneNumber = ""
			lastDatabaseDirectory = ""
			lastFilesDirectory = ""
			lastDatabaseEncryptionKey = ByteArray(0)
			lastApiId = 0
			lastApiHash = ""
			initialAuthorizationState = TdApi.AuthorizationStateWaitTdlibParameters()
			authorizationStateAfterTdlibParameters =
				TdApi.AuthorizationStateWaitPhoneNumber()
			chatsById.clear()
			messagesByChatId.clear()
			maxChatLoadPageSize = Int.MAX_VALUE
			maxHistoryPageSize = Int.MAX_VALUE
			activeDatabaseDirectory = ""
			setTdlibParametersAcceptedLatch = null
			rejectSetTdlibParameters = false
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
		fun setMaxChatLoadPageSize(limit: Int) {
			maxChatLoadPageSize = limit.coerceAtLeast(0)
		}

		@JvmStatic
		fun setMaxHistoryPageSize(limit: Int) {
			maxHistoryPageSize = limit.coerceAtLeast(1)
		}

		@JvmStatic
		fun prepareSetTdlibParametersAcceptedLatch() {
			setTdlibParametersAcceptedLatch = CountDownLatch(1)
		}

		@JvmStatic
		fun setRejectSetTdlibParameters(reject: Boolean) {
			rejectSetTdlibParameters = reject
		}

		@JvmStatic
		fun awaitSetTdlibParametersAccepted(timeoutMs: Long): Boolean =
			setTdlibParametersAcceptedLatch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: false

		@JvmStatic
		fun getSentRequestNames(): List<String> = synchronized(stateLock) {
			sentRequestNames.toList()
		}

		@JvmStatic
		fun getLastPhoneNumber(): String = lastPhoneNumber

		@JvmStatic
		fun getLastDatabaseDirectory(): String = lastDatabaseDirectory

		@JvmStatic
		fun getLastFilesDirectory(): String = lastFilesDirectory

		@JvmStatic
		fun getLastDatabaseEncryptionKey(): ByteArray = lastDatabaseEncryptionKey.copyOf()

		@JvmStatic
		fun getLastApiId(): Int = lastApiId

		@JvmStatic
		fun getLastApiHash(): String = lastApiHash

		private fun getAuthorizationUpdateDelayMs(): Long = synchronized(stateLock) {
			if (authorizationUpdateDelaySequenceMs.isNotEmpty()) {
				authorizationUpdateDelaySequenceMs.removeAt(0)
			} else {
				authorizationUpdateDelayMs
			}
		}

		private fun recordSentRequest(requestName: String) {
			synchronized(stateLock) {
				sentRequestNames += requestName
			}
		}

		private fun authorizationStateAfterCode(code: String): Any = when {
			code.contains("password-required") -> TdApi.AuthorizationStateWaitPassword()
			code.contains("registration-required") -> TdApi.AuthorizationStateWaitRegistration()
			else -> TdApi.AuthorizationStateReady()
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
			return (index + request.offset + 1).coerceIn(0, messages.size)
		}
	}
}
