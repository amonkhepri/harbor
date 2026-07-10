package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramMessage
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.LinkedHashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

interface TelegramTdlibMessageClient {
	fun isAuthorized(): Boolean
	fun getRecentChats(limit: Int): List<TelegramChat>
	fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage>
}

class NoOpTelegramTdlibMessageClient : TelegramTdlibMessageClient {
	override fun isAuthorized(): Boolean = false

	override fun getRecentChats(limit: Int): List<TelegramChat> = emptyList()

	override fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage> = emptyList()
}

class ReflectiveTelegramTdlibMessageClient @JvmOverloads constructor(
	private val tdlibDirectory: File = File("harbor-telegram"),
	private val apiId: Int = 0,
	private val apiHash: String = "",
	private val tdlibKeyProvider: TelegramTdlibDatabaseKeyProvider =
		NoOpTelegramTdlibDatabaseKeyProvider,
	private val requestTimeoutMs: Long = 30_000L,
) : TelegramTdlibMessageClient {

	private val tdlibReadLock = Any()

	override fun isAuthorized(): Boolean = withReadyClient(false) { true }

	override fun getRecentChats(limit: Int): List<TelegramChat> {
		val safeLimit = safeLimit(limit)
		if (safeLimit == 0) return emptyList()
		return withReadyClient(emptyList()) { client ->
			sendAndAwait(client, createLoadChatsRequest(safeLimit))
			val chatIds = sendAndAwait(client, createGetChatsRequest(safeLimit))
				?.let { getLongArrayField(it, "chatIds") }
				?: LongArray(0)
			chatIds.take(safeLimit).mapNotNull { chatId ->
				sendAndAwait(client, createGetChatRequest(chatId))?.let { mapChat(it) }
			}
		}
	}

	override fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage> {
		val safeLimit = safeLimit(limit)
		if (chatId == 0L || safeLimit == 0) return emptyList()
		return withReadyClient(emptyList()) { client ->
			val messages = mutableListOf<TelegramMessage>()
			val seenMessageIds = LinkedHashSet<Long>()
			var fromMessageId = 0L
			var requestCount = 0
			while (messages.size < safeLimit && requestCount < MAX_TDLIB_HISTORY_REQUESTS) {
				val rawMessages = sendAndAwait(
					client,
					createGetChatHistoryRequest(chatId, fromMessageId, safeLimit),
				)?.let { getObjectArrayField(it, "messages") }
					?: emptyArray<Any>()
				if (rawMessages.isEmpty()) break
				var newRawMessage = false
				for (rawMessage in rawMessages) {
					val messageId = getMessageId(rawMessage) ?: continue
					if (!seenMessageIds.add(messageId)) continue
					newRawMessage = true
					val message = mapTextMessage(rawMessage) ?: continue
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
			messages
		}
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
				if (sendReturnsError(client, parametersRequest)) return fallback
				authorizationState = awaitPreparedAuthorizationStateClassName(readyUpdate)
			}
			if (authorizationState != "AuthorizationStateReady") return fallback
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
	): String = if (pendingAuthorizationUpdate.updateReceived.await(
			requestTimeoutMs,
			TimeUnit.MILLISECONDS,
		)
	) {
		pendingAuthorizationUpdate.authorizationStateClassName.get()
	} else {
		""
	}

	@Throws(ReflectiveOperationException::class)
	private fun createTdlibClient(
		pendingAuthorizationUpdate: AtomicReference<PendingAuthorizationUpdate?>,
	): Any {
		val clientClass = Class.forName("org.drinkless.tdlib.Client")
		val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
		val exceptionHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ExceptionHandler")
		val updateHandler = Proxy.newProxyInstance(
			resultHandlerClass.classLoader,
			arrayOf(resultHandlerClass),
		) { _, method, args ->
			if (method.name == "onResult" && args != null && args.size == 1) {
				val className = getAuthorizationStateClassName(args[0])
				val pendingUpdate = pendingAuthorizationUpdate.get()
				pendingUpdate?.capture(className)
			}
			null
		}
		val create = clientClass.getMethod(
			"create",
			resultHandlerClass,
			exceptionHandlerClass,
			exceptionHandlerClass,
		)
		return create.invoke(null, updateHandler, null, null)
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
		val result = AtomicReference<Any?>()
		val resultReceived = CountDownLatch(1)
		val functionClass = tdApiClass("Function")
		val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
		val resultHandler = Proxy.newProxyInstance(
			resultHandlerClass.classLoader,
			arrayOf(resultHandlerClass),
		) { _, method, args ->
			if (method.name == "onResult" &&
				args != null &&
				args.size == 1 &&
				result.compareAndSet(null, args[0])
			) {
				resultReceived.countDown()
			}
			null
		}
		client.javaClass.getMethod("send", functionClass, resultHandlerClass)
			.invoke(client, request, resultHandler)
		if (!resultReceived.await(requestTimeoutMs, TimeUnit.MILLISECONDS)) return null
		return result.get().takeUnless { it?.javaClass?.simpleName == "Error" }
	}

	@Throws(ReflectiveOperationException::class, InterruptedException::class)
	private fun sendReturnsError(client: Any, request: Any): Boolean =
		sendAndAwait(client, request) == null

	@Throws(ReflectiveOperationException::class)
	private fun mapChat(chat: Any): TelegramChat? {
		if (chat.javaClass.simpleName != "Chat") return null
		val lastMessage = getObjectField(chat, "lastMessage")
		val lastTextMessage = mapTextMessage(lastMessage)
		return TelegramChat(
			id = getLongField(chat, "id"),
			title = getStringField(chat, "title"),
			lastMessageDateSeconds = lastMessage?.let { getIntField(it, "date") } ?: 0,
			lastMessageText = lastTextMessage?.text.orEmpty(),
			lastMessageIsOutgoing = lastTextMessage?.isOutgoing ?: false,
		)
	}

	@Throws(ReflectiveOperationException::class)
	private fun mapTextMessage(message: Any?): TelegramMessage? {
		if (message == null || message.javaClass.simpleName != "Message") return null
		val content = getObjectField(message, "content")
		if (content?.javaClass?.simpleName != "MessageText") return null
		val formattedText = getObjectField(content, "text")
		return TelegramMessage(
			chatId = getLongField(message, "chatId"),
			messageId = getLongField(message, "id"),
			dateSeconds = getIntField(message, "date"),
			isOutgoing = getBooleanField(message, "isOutgoing"),
			text = formattedText?.let { getStringField(it, "text") }.orEmpty(),
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
			val functionClass = tdApiClass("Function")
			val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
			val send: Method = client.javaClass.getMethod("send", functionClass, resultHandlerClass)
			val closeRequest = createTdApiObject("Close")
			send.invoke(client, closeRequest, null)
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
		const val MAX_TDLIB_HISTORY_REQUESTS = 5
	}
}
