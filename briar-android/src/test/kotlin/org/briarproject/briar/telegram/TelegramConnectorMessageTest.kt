package org.briarproject.briar.telegram

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.LoadFailed
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.Success
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.telegram.TelegramConnector
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun telegramMessage(
	chatId: Long,
	messageId: Long,
	dateSeconds: Int,
	isOutgoing: Boolean,
	text: String,
) = ConnectorMessage(
	ConnectorSources.TELEGRAM,
	chatId.toString(),
	messageId.toString(),
	dateSeconds,
	isOutgoing,
	text,
	messageId,
)

private fun telegramThread(
	chatId: Long,
	title: String,
	latestActivityDateSeconds: Int,
	latestMessageText: String = "",
	isLatestMessageOutgoing: Boolean = false,
) = ConnectorThread(
	ConnectorSources.TELEGRAM,
	chatId.toString(),
	title,
	latestActivityDateSeconds,
	latestMessageText,
	isLatestMessageOutgoing,
)

class TelegramConnectorMessageTest {
	@get:Rule
	val testFolder = TemporaryFolder()

	private fun assertSentRequests(vararg requestNames: String) {
		assertEquals(requestNames.toList(), Client.getSentRequestNames())
	}

	private fun FakeTelegramConnector.lastRequestLimits() =
		Triple(lastChatLimit, lastMessageChatId, lastMessageLimit)

	private fun readMessages(
		client: TelegramConnector,
		chatId: Long,
		limit: Int,
	): List<ConnectorMessage> = (client.getRecentMessageReadResult(chatId, limit) as Success).messages

	@Test
	fun testDisabledConnectorReturnsDisabledEmptyMessageLists() {
		val connector = DisabledTelegramConnector()

		assertEquals(listOf(false, false), listOf(connector.isEnabled(), connector.isAuthorized()))
		assertEquals(
			emptyList<ConnectorThread>() to Success(emptyList()),
			connector.getRecentThreads(5) to connector.getRecentMessageReadResult(1L, 5),
		)
	}

	@Test
	fun testEnabledConnectorExposesReadOnlyConnectorContract() {
		val connector = FakeTelegramConnector(
			chats = listOf(
				telegramThread(
					10L,
					"synthetic",
					1_700_000_000,
					"preview",
					true,
				),
			),
			messages = listOf(
				telegramMessage(
					10L,
					20L,
					1_700_000_001,
					false,
					"body",
				),
			),
		)

		assertEquals(listOf(true, true), listOf(connector.isEnabled(), connector.isAuthorized()))
		assertEquals(ConnectorSources.TELEGRAM, connector.source)
		assertEquals(
			listOf(
				ConnectorThread(
					ConnectorSources.TELEGRAM,
					"10",
					"synthetic",
					1_700_000_000,
					"preview",
					true,
				),
			) to
				ConnectorMessageReadResult.Success(
					listOf(
						ConnectorMessage(
							ConnectorSources.TELEGRAM,
							"10",
							"20",
							1_700_000_001,
							false,
							"body",
							20L,
						),
					),
				),
			connector.getRecentThreads(3) to
				connector.getRecentMessageReadResult("10", 2),
		)
		assertEquals(Triple(3, 10L, 2), connector.lastRequestLimits())
	}

	@Test
	fun testReadOnlyConnectorRejectsNonNumericThreadId() {
		val connector = FakeTelegramConnector()

		assertEquals(
			ConnectorMessageReadResult.LoadFailed,
			connector.getRecentMessageReadResult("thread-1", 2),
		)
		assertEquals(Triple(0, 0L, 0), connector.lastRequestLimits())
	}

	@Test
	fun testReadOnlyConnectorPreservesLoadFailureResult() {
		val connector = FakeTelegramConnector(messageReadResult = LoadFailed)

		assertEquals(
			ConnectorMessageReadResult.LoadFailed,
			connector.getRecentMessageReadResult("10", 2),
		)
	}

	@Test
	fun testReflectiveClientMapsRecentChatsAndTextMessages() {
		val client = readyReflectiveMessageClient {
			Client.setChats(
				chat(
					10L,
					lastMessageDateSeconds = 1_700_000_000,
					body = "chat preview",
					isOutgoing = true,
				),
			)
			Client.setMessages(
				10L,
				textMessage(10L, 20L, 1_700_000_001, isOutgoing = false),
				photoMessage(10L, 21L, 1_700_000_002),
			)
		}

		assertEquals(
			listOf(
				telegramThread(
					10L,
					"",
					1_700_000_000,
					"chat preview",
					true,
				),
			) to
				listOf(
					telegramMessage(10L, 20L, 1_700_000_001, false, ""),
				),
			client.getRecentThreads(3) to readMessages(client, 10L, 3),
		)
	}

	@Test
	fun testReflectiveClientLoadsPartialChatPagesUntilLimit() {
		val client = readyReflectiveMessageClient {
			Client.setMaxChatLoadPageSize(1)
			Client.setChats(
				chat(10L, lastMessageDateSeconds = 1_700_000_003),
				chat(11L, lastMessageDateSeconds = 1_700_000_002),
				chat(12L, lastMessageDateSeconds = 1_700_000_001),
			)
		}

		assertEquals(listOf("10", "11", "12"), client.getRecentThreads(3).map { it.threadId })
		assertSentRequests(
			"LoadChats",
			"GetChats",
			"LoadChats",
			"GetChats",
			"LoadChats",
			"GetChats",
			"GetChat",
			"GetChat",
			"GetChat",
			"Close",
		)
	}

	@Test
	fun testReflectiveClientPaginatesPartialHistoryPages() {
		val client = readyReflectiveMessageClient {
			Client.setMaxHistoryPageSize(1)
			Client.setMessages(
				10L,
				textMessage(10L, 40L, 1_700_000_004, isOutgoing = false, body = "latest"),
				textMessage(10L, 30L, 1_700_000_003, isOutgoing = false, body = "middle"),
				textMessage(10L, 20L, 1_700_000_002, isOutgoing = true, body = "older"),
				textMessage(10L, 10L, 1_700_000_001, isOutgoing = false, body = "oldest"),
			)
		}

		val messages = readMessages(client, 10L, 4)

		assertEquals(
			listOf(40L, 30L, 20L, 10L) to listOf("latest", "middle", "older", "oldest"),
			messages.map { it.sourceMessageOrder } to messages.map { it.text },
		)
		assertSentRequests(
			"GetChatHistory",
			"GetChatHistory",
			"GetChatHistory",
			"GetChatHistory",
			"Close",
		)
	}

	@Test
	fun testReflectiveClientDistinguishesHistoryErrorFromEmptyConversation() {
		val emptyClient = readyReflectiveMessageClient {
			Client.setMessages(10L)
		}
		assertEquals(
			Success(emptyList()),
			emptyClient.getRecentMessageReadResult(10L, 3),
		)

		val failingClient = readyReflectiveMessageClient {
			Client.setMessages(
				10L,
				textMessage(10L, 40L, 1_700_000_004, isOutgoing = false, body = "latest"),
			)
			Client.setRejectGetChatHistory(true)
		}

		assertEquals(LoadFailed, failingClient.getRecentMessageReadResult(10L, 3))
	}

	@Test
	fun testReflectiveClientUsesDatabaseEncryptionKeyWhenSettingParameters() {
		Client.resetTestState()
		Client.setAuthorizationStateAfterTdlibParameters(TdApi.AuthorizationStateReady())
		val client = configuredReflectiveMessageClient()

		assertEquals(true, client.isAuthorized())
		assertArrayEquals(TDLIB_KEY, Client.getLastDatabaseEncryptionKey())
		assertSentRequests("SetTdlibParameters", "Close")
	}

	@Test
	fun testReflectiveClientStopsBeforeParametersWithoutDatabaseEncryptionKey() {
		Client.resetTestState()
		val tdlibDir = testFolder.newFolder("tdlib-no-key")
		val client = ReflectiveTelegramTdlibMessageClient(
			tdlibDirectory = tdlibDir,
			apiId = 1,
			apiHash = "x",
			requestTimeoutMs = 1_000L,
		)

		assertEquals(
			false to (emptyList<ConnectorThread>() to LoadFailed),
			client.isAuthorized() to
				(client.getRecentThreads(1) to client.getRecentMessageReadResult(10L, 1)),
		)
		assertEquals(false, File(tdlibDir, "database").exists())
	}

	@Test
	fun testReflectiveClientSerializesConcurrentReadsForSharedTdlibDirectory() {
		Client.resetTestState()
		Client.setAuthorizationStateAfterTdlibParameters(TdApi.AuthorizationStateReady())
		Client.setAuthorizationUpdateDelaySequenceMs(0L, 1_000L, 0L, 0L)
		Client.setChats(chat(11L, 1_700_000_003, body = "preview"))
		Client.setMessages(
			11L,
			textMessage(11L, 21L, 1_700_000_004, isOutgoing = false, body = "body"),
		)
		Client.prepareSetTdlibParametersAcceptedLatch()
		val client = configuredReflectiveMessageClient(
			tdlibDirectory = testFolder.newFolder("tdlib-concurrent"),
			requestTimeoutMs = 2_000L,
		)
		val chatsResult = arrayOf<List<ConnectorThread>?>(null)
		val chatsThread = Thread {
			chatsResult[0] = client.getRecentThreads(1)
		}

		chatsThread.start()
		assertEquals(true, Client.awaitSetTdlibParametersAccepted(1_000L))
		val messages = readMessages(client, 11L, 1)
		chatsThread.join()

		assertEquals(
			listOf(telegramThread(11L, "", 1_700_000_003, "preview", false)),
			chatsResult[0],
		)
		assertEquals(
			listOf(
				telegramMessage(11L, 21L, 1_700_000_004, false, "body"),
			),
			messages,
		)
	}

	private fun readyReflectiveMessageClient(
		configureClientState: () -> Unit = {},
	): ReflectiveTelegramTdlibMessageClient {
		Client.resetTestState()
		Client.setInitialAuthorizationState(TdApi.AuthorizationStateReady())
		configureClientState()
		return ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)
	}

	private fun configuredReflectiveMessageClient(
		tdlibDirectory: File = testFolder.newFolder("tdlib"),
		requestTimeoutMs: Long = 1_000L,
	) = ReflectiveTelegramTdlibMessageClient(
		tdlibDirectory = tdlibDirectory,
		apiId = 1,
		apiHash = "x",
		tdlibKeyProvider = StaticTelegramTdlibDatabaseKeyProvider(TDLIB_KEY),
		requestTimeoutMs = requestTimeoutMs,
	)

	private class StaticTelegramTdlibDatabaseKeyProvider(private val key: ByteArray) :
		TelegramTdlibDatabaseKeyProvider {
		override fun getDatabaseEncryptionKey(tdlibDirectory: File): ByteArray = key.copyOf()
	}

	private class FakeTelegramConnector(
		val authorized: Boolean = true,
		val chats: List<ConnectorThread> = listOf(telegramThread(10L, "", 1_700_000_000)),
		val messages: List<ConnectorMessage> = listOf(
			telegramMessage(10L, 20L, 1_700_000_001, false, ""),
		),
		val messageReadResult: ConnectorMessageReadResult? = null,
	) : TelegramConnector {
		var lastChatLimit = 0
		var lastMessageChatId = 0L
		var lastMessageLimit = 0

		override fun isEnabled(): Boolean = true

		override fun isAuthorized(): Boolean = authorized

		override fun getRecentThreads(limit: Int): List<ConnectorThread> {
			lastChatLimit = limit
			return chats
		}

		override fun getRecentMessageReadResult(chatId: Long, limit: Int): ConnectorMessageReadResult {
			lastMessageChatId = chatId
			lastMessageLimit = limit
			return messageReadResult ?: Success(messages)
		}
	}

	private fun chat(
		id: Long,
		lastMessageDateSeconds: Int,
		body: String = "",
		isOutgoing: Boolean = false,
	): TdApi.Chat = TdApi.Chat().also {
		it.id = id
		it.title = ""
		it.lastMessage = textMessage(
			id,
			id * 10,
			lastMessageDateSeconds,
			isOutgoing,
			body,
		)
	}

	private fun textMessage(
		chatId: Long,
		messageId: Long,
		dateSeconds: Int,
		isOutgoing: Boolean,
		body: String = "",
	): TdApi.Message = TdApi.Message().also {
		it.chatId = chatId
		it.id = messageId
		it.date = dateSeconds
		it.isOutgoing = isOutgoing
		it.content = TdApi.MessageText().also { content ->
			content.text = TdApi.FormattedText().also { text ->
				text.text = body
			}
		}
	}

	private fun photoMessage(chatId: Long, messageId: Long, dateSeconds: Int): TdApi.Message =
		TdApi.Message().also {
			it.chatId = chatId
			it.id = messageId
			it.date = dateSeconds
			it.content = TdApi.MessagePhoto()
		}

	private companion object {
		private val TDLIB_KEY = ByteArray(32) { (it + 2).toByte() }
	}
}
