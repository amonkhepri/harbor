package org.briarproject.briar.telegram

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.LoadFailed
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.Success
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.telegram.TelegramConnector
import java.io.File
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicReference

class ReflectiveTelegramTdlibMessageClient(
	private val tdlibDirectory: File = File("harbor-telegram"),
	private val apiId: Int = 0,
	private val apiHash: String = "",
	private val tdlibKeyProvider: TelegramTdlibDatabaseKeyProvider =
		NoOpTelegramTdlibDatabaseKeyProvider,
	private val requestTimeoutMs: Long = 30_000L,
	private val accessGate: TelegramTdlibAccessGate = TelegramTdlibAccessGate(),
) : TelegramConnector {

	private val tdlibReadLock = Any()

	override fun isEnabled(): Boolean = true

	override fun isAuthorized(): Boolean = accessGate.run(false) { withReadyClient(false) { true } }

	override fun getRecentThreads(limit: Int): List<ConnectorThread> {
		val safeLimit = safeLimit(limit)
		if (safeLimit == 0) return emptyList()
		return accessGate.run(emptyList()) {
			withReadyClient(emptyList()) { client ->
				loadRecentChatIds(client, safeLimit).take(safeLimit).mapNotNull { chatId ->
					sendAndAwait(client, createGetChatRequest(chatId))?.let { mapChat(it) }
				}
			}
		}
	}

	override fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult {
		val chatId = threadId.toLongOrNull() ?: return LoadFailed
		val safeLimit = safeLimit(limit)
		if (chatId == 0L || safeLimit == 0) return Success(emptyList())
		return accessGate.run<ConnectorMessageReadResult?>(null) {
			withReadyClient(null) { client ->
				val messages = mutableListOf<ConnectorMessage>()
				val seenMessageIds = LinkedHashSet<Long>()
				var fromMessageId = 0L
				var requestCount = 0
				while (messages.size < safeLimit && requestCount < MAX_TDLIB_HISTORY_REQUESTS) {
					val historyPage = sendAndAwait(
						client,
						createGetChatHistoryRequest(chatId, fromMessageId, safeLimit),
					) ?: return@withReadyClient LoadFailed
					val rawMessages = getObjectArrayField(historyPage, "messages")
					if (rawMessages.isEmpty()) break
					var newRawMessage = false
					for (rawMessage in rawMessages) {
						val messageId = getMessageId(rawMessage) ?: continue
						if (!seenMessageIds.add(messageId)) continue
						newRawMessage = true
						val message = mapMessage(rawMessage) ?: continue
						if (messages.size < safeLimit) messages += message
					}
					requestCount++
					val nextFromMessageId = rawMessages.mapNotNull { getMessageId(it) }.lastOrNull()
					if (!newRawMessage ||
						nextFromMessageId == null ||
						nextFromMessageId == fromMessageId
					) {
						break
					}
					fromMessageId = nextFromMessageId
				}
				Success(messages)
			}
		} ?: LoadFailed
	}

	@Throws(ReflectiveOperationException::class, InterruptedException::class)
	private fun loadRecentChatIds(client: Any, safeLimit: Int): LongArray {
		var chatIds = LongArray(0)
		var requestCount = 0
		while (chatIds.size < safeLimit && requestCount < MAX_TDLIB_CHAT_LOAD_REQUESTS) {
			val loadResult = sendAndAwait(client, createLoadChatsRequest(safeLimit))
			val loadedChatIds = sendAndAwait(client, createGetChatsRequest(safeLimit))
				?.let { getLongArrayField(it, "chatIds") }
				?: LongArray(0)
			requestCount++
			val countStoppedGrowing = loadedChatIds.size <= chatIds.size
			chatIds = loadedChatIds
			if (countStoppedGrowing || loadResult == null) break
		}
		return chatIds
	}

	private fun <T> withReadyClient(fallback: T, read: (Any) -> T): T = synchronized(tdlibReadLock) {
		if (!tdlibClientClassExists()) return fallback
		var client: Any? = null
		val pendingAuthorizationUpdate = AtomicReference<PendingAuthorizationUpdate?>()
		return try {
			val firstUpdate = prepareAuthorizationUpdate(pendingAuthorizationUpdate)
			client = createTdlibClient(pendingAuthorizationUpdate)
			var authorizationState = awaitPreparedAuthorizationStateClassName(firstUpdate)
			if (authorizationState == "AuthorizationStateWaitTdlibParameters") {
				if (apiId <= 0 || !hasText(apiHash)) return fallback
				val parametersRequest = createTelegramTdlibParametersRequest(
					tdlibKeyProvider,
					tdlibDirectory,
					apiId,
					apiHash,
				) ?: return fallback
				val readyUpdate = prepareAuthorizationUpdate(pendingAuthorizationUpdate)
				if (sendAndAwait(client, parametersRequest) == null) return fallback
				authorizationState = awaitPreparedAuthorizationStateClassName(readyUpdate)
			}
			if (authorizationState != "AuthorizationStateReady") return fallback
			Thread.sleep(3_000L)
			read(client)
		} catch (e: ReflectiveOperationException) {
			fallback
		} catch (e: LinkageError) {
			fallback
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			fallback
		} finally {
			client?.let { closeTdlibClient(it, pendingAuthorizationUpdate) }
		}
	}

	private fun prepareAuthorizationUpdate(
		pendingAuthorizationUpdate: AtomicReference<PendingAuthorizationUpdate?>,
		acceptedClassName: String? = null,
	): PendingAuthorizationUpdate = PendingAuthorizationUpdate(acceptedClassName).also {
		pendingAuthorizationUpdate.set(it)
	}

	@Throws(InterruptedException::class)
	private fun awaitPreparedAuthorizationStateClassName(
		pendingAuthorizationUpdate: PendingAuthorizationUpdate,
	): String = pendingAuthorizationUpdate.await(requestTimeoutMs) ?: ""

	@Throws(ReflectiveOperationException::class)
	private fun createTdlibClient(
		pendingAuthorizationUpdate: AtomicReference<PendingAuthorizationUpdate?>,
	): Any = createReflectiveTdlibClient { update ->
		val className = getAuthorizationStateClassName(update)
		val pendingUpdate = pendingAuthorizationUpdate.get()
		pendingUpdate?.capture(className)
	}

	@Throws(ReflectiveOperationException::class)
	private fun createLoadChatsRequest(limit: Int): Any = createTdApiObject("LoadChats").also {
		setFieldIfPresent(it, "chatList", null)
		setFieldIfPresent(it, "limit", limit)
	}

	@Throws(ReflectiveOperationException::class)
	private fun createGetChatsRequest(limit: Int): Any = createTdApiObject("GetChats").also {
		setFieldIfPresent(it, "chatList", null)
		setFieldIfPresent(it, "limit", limit)
	}

	@Throws(ReflectiveOperationException::class)
	private fun createGetChatRequest(chatId: Long): Any = createTdApiObject("GetChat").also {
		setFieldIfPresent(it, "chatId", chatId)
	}

	@Throws(ReflectiveOperationException::class)
	private fun createGetChatHistoryRequest(chatId: Long, fromMessageId: Long, limit: Int): Any =
		createTdApiObject("GetChatHistory").also {
			setFieldIfPresent(it, "chatId", chatId)
			setFieldIfPresent(it, "fromMessageId", fromMessageId)
			setFieldIfPresent(it, "offset", 0)
			setFieldIfPresent(it, "limit", limit)
			setFieldIfPresent(it, "onlyLocal", false)
		}

	@Throws(ReflectiveOperationException::class, InterruptedException::class)
	private fun sendAndAwait(client: Any, request: Any): Any? {
		val result = sendReflectiveTdlibRequest(client, request, requestTimeoutMs)
		return result.value.takeIf { result.completed && it?.javaClass?.simpleName != "Error" }
	}

	@Throws(ReflectiveOperationException::class)
	private fun mapChat(chat: Any): ConnectorThread? {
		if (chat.javaClass.simpleName != "Chat") return null
		val lastMessage = getObjectField(chat, "lastMessage")
		val latestMessage = mapMessage(lastMessage)
		return ConnectorThread(
			source = ConnectorSources.TELEGRAM,
			threadId = getLongField(chat, "id").toString(),
			title = getStringField(chat, "title"),
			latestActivityDateSeconds = lastMessage?.let { getIntField(it, "date") } ?: 0,
			latestMessageText = latestMessage?.text.orEmpty(),
			isLatestMessageOutgoing = lastMessage?.let { getBooleanField(it, "isOutgoing") } ?: false,
			latestMessageType = latestMessage?.type ?: ConnectorMessageType.TEXT,
		)
	}

	@Throws(ReflectiveOperationException::class)
	private fun mapMessage(message: Any?): ConnectorMessage? {
		if (message == null || message.javaClass.simpleName != "Message") return null
		val content = getObjectField(message, "content")
		val type = when (content?.javaClass?.simpleName) {
			"MessageText" -> ConnectorMessageType.TEXT
			"MessagePhoto" -> ConnectorMessageType.PHOTO
			else -> return null
		}
		val formattedText = if (type == ConnectorMessageType.TEXT) {
			getObjectField(content, "text")
		} else {
			null
		}
		val chatId = getLongField(message, "chatId")
		val messageId = getLongField(message, "id")
		return ConnectorMessage(
			source = ConnectorSources.TELEGRAM,
			threadId = chatId.toString(),
			messageId = messageId.toString(),
			dateSeconds = getIntField(message, "date"),
			isOutgoing = getBooleanField(message, "isOutgoing"),
			text = formattedText?.let { getStringField(it, "text") }.orEmpty(),
			sourceMessageOrder = messageId,
			type = type,
		)
	}

	@Throws(ReflectiveOperationException::class)
	private fun getMessageId(message: Any?): Long? {
		if (message == null || message.javaClass.simpleName != "Message") return null
		return getLongField(message, "id").takeIf { it != 0L }
	}

	@Throws(ReflectiveOperationException::class)
	private fun getObjectField(target: Any, name: String): Any? =
		target.javaClass.getField(name).get(target)

	@Throws(ReflectiveOperationException::class)
	private fun getStringField(target: Any, name: String): String =
		getObjectField(target, name) as? String ?: ""

	@Throws(ReflectiveOperationException::class)
	private fun getLongField(target: Any, name: String): Long =
		getObjectField(target, name) as? Long ?: 0L

	@Throws(ReflectiveOperationException::class)
	private fun getIntField(target: Any, name: String): Int = getObjectField(target, name) as? Int ?: 0

	@Throws(ReflectiveOperationException::class)
	private fun getBooleanField(target: Any, name: String): Boolean =
		getObjectField(target, name) as? Boolean ?: false

	@Throws(ReflectiveOperationException::class)
	private fun getLongArrayField(target: Any, name: String): LongArray =
		getObjectField(target, name) as? LongArray ?: LongArray(0)

	@Throws(ReflectiveOperationException::class)
	private fun getObjectArrayField(target: Any, name: String): Array<*> =
		getObjectField(target, name) as? Array<*> ?: emptyArray<Any>()

	private fun closeTdlibClient(
		client: Any,
		pendingAuthorizationUpdate: AtomicReference<PendingAuthorizationUpdate?>,
	) {
		try {
			val closeUpdate = prepareAuthorizationUpdate(
				pendingAuthorizationUpdate,
				"AuthorizationStateClosed",
			)
			closeReflectiveTdlibClient(client)
			awaitPreparedAuthorizationStateClassName(closeUpdate)
		} catch (_: ReflectiveOperationException) {
		} catch (_: LinkageError) {
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
		} finally {
			pendingAuthorizationUpdate.set(null)
		}
	}

	private fun safeLimit(limit: Int): Int = limit.coerceIn(0, MAX_TDLIB_MESSAGE_LIMIT)

	private companion object {
		const val MAX_TDLIB_MESSAGE_LIMIT = 100
		const val MAX_TDLIB_CHAT_LOAD_REQUESTS = 5
		const val MAX_TDLIB_HISTORY_REQUESTS = 5
	}
}
