package org.briarproject.briar.telegram

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramMessage
import org.briarproject.briar.api.telegram.TelegramMessageIngestSnapshot
import org.briarproject.briar.api.telegram.TelegramMessageIngestStatus
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelegramConnectorMessageTest {

	@get:Rule
	val testFolder = TemporaryFolder()

	private fun assertSentRequests(vararg requestNames: String) {
		assertEquals(requestNames.toList(), Client.getSentRequestNames())
	}

	private fun FakeTelegramTdlibMessageClient.lastRequestLimits() =
		Triple(lastChatLimit, lastMessageChatId, lastMessageLimit)

	@Test
	fun testNoOpConnectorReturnsDisabledEmptyMessageLists() {
		val connector = NoOpTelegramConnector()

		assertEquals(listOf(false, false), listOf(connector.isEnabled(), connector.isAuthorized()))
		assertEquals(
			emptyList<TelegramChat>() to emptyList<TelegramMessage>(),
			connector.getRecentChats(5) to connector.getRecentMessages(1L, 5),
		)
	}

	@Test
	fun testEnabledConnectorDelegatesRecentChatsAndMessagesToClient() {
		val client = FakeTelegramTdlibMessageClient()
		val connector = StubTelegramConnector(client)

		assertEquals(listOf(true, true), listOf(connector.isEnabled(), connector.isAuthorized()))
		assertEquals(
			client.chats to client.messages,
			connector.getRecentChats(3) to connector.getRecentMessages(10L, 2),
		)
		assertEquals(Triple(3, 10L, 2), client.lastRequestLimits())
	}

	@Test
	fun testEnabledConnectorExposesReadOnlyConnectorContract() {
		val client = FakeTelegramTdlibMessageClient(
			chats = listOf(
				TelegramChat(
					10L,
					"synthetic",
					1_700_000_000,
					"preview",
					true,
				),
			),
			messages = listOf(
				TelegramMessage(
					10L,
					20L,
					1_700_000_001,
					false,
					"body",
				),
			),
		)
		val connector = StubTelegramConnector(client)

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
			connector.getRecentThreads(3) to
				connector.getRecentMessages("10", 2),
		)
	}

	@Test
	fun testReadOnlyConnectorReturnsEmptyForNonNumericThreadId() {
		val client = FakeTelegramTdlibMessageClient()
		val connector = StubTelegramConnector(client)

		assertEquals(emptyList<ConnectorMessage>(), connector.getRecentMessages("thread-1", 2))
		assertEquals(Triple(0, 0L, 0), client.lastRequestLimits())
	}

	@Test
	fun testReflectiveClientReturnsEmptyWhenAuthorizationIsNotReady() {
		Client.resetTestState()
		val client = ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)

		assertEquals(
			false to (emptyList<TelegramChat>() to emptyList<TelegramMessage>()),
			client.isAuthorized() to (client.getRecentChats(5) to client.getRecentMessages(10L, 5)),
		)
		assertEquals(
			false,
			Client.getSentRequestNames().any {
				it in listOf("GetChats", "GetChatHistory")
			},
		)
	}

	@Test
	fun testReflectiveClientReportsAuthorizationReady() {
		val client = readyReflectiveMessageClient()

		assertEquals(
			true to listOf("Close"),
			client.isAuthorized() to Client.getSentRequestNames(),
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
				TelegramChat(
					10L,
					"",
					1_700_000_000,
					"chat preview",
					true,
				),
			) to
				listOf(
					TelegramMessage(
						chatId = 10L,
						messageId = 20L,
						dateSeconds = 1_700_000_001,
						isOutgoing = false,
						text = "",
					),
				),
			client.getRecentChats(3) to client.getRecentMessages(10L, 3),
		)
		assertSentRequests(
			"GetChats",
			"GetChat",
			"Close",
			"GetChatHistory",
			"GetChatHistory",
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

		val messages = client.getRecentMessages(10L, 4)

		assertEquals(
			listOf(40L, 30L, 20L, 10L) to listOf("latest", "middle", "older", "oldest"),
			messages.map { it.messageId } to messages.map { it.text },
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
	fun testReflectiveClientSetsTdlibParametersBeforeReading() {
		Client.resetTestState()
		Client.setAuthorizationStateAfterTdlibParameters(TdApi.AuthorizationStateReady())
		Client.setChats(chat(11L, lastMessageDateSeconds = 1_700_000_003))
		val client = ReflectiveTelegramTdlibMessageClient(
			tdlibDirectory = testFolder.newFolder("tdlib"),
			apiId = 1,
			apiHash = "x",
			tdlibKeyProvider = StaticTelegramTdlibDatabaseKeyProvider(TDLIB_KEY),
			requestTimeoutMs = 1_000L,
		)

		assertEquals(listOf(TelegramChat(11L, "", 1_700_000_003)), client.getRecentChats(1))
		assertArrayEquals(TDLIB_KEY, Client.getLastDatabaseEncryptionKey())
		assertSentRequests("SetTdlibParameters", "GetChats", "GetChat", "Close")
	}

	@Test
	fun testReflectiveClientStopsBeforeParametersWithoutDatabaseEncryptionKey() {
		Client.resetTestState()
		Client.setAuthorizationStateAfterTdlibParameters(TdApi.AuthorizationStateReady())
		val tdlibDir = testFolder.newFolder("tdlib-no-key")
		val client = ReflectiveTelegramTdlibMessageClient(
			tdlibDirectory = tdlibDir,
			apiId = 1,
			apiHash = "x",
			requestTimeoutMs = 1_000L,
		)

		assertEquals(emptyList<TelegramChat>(), client.getRecentChats(1))
		assertEquals(false, File(tdlibDir, "database").exists())
		assertSentRequests("Close")
	}

	@Test
	fun testDiagnosticsWaitsForAuthorizationProbeCloseBeforeReading() {
		Client.resetTestState()
		Client.setAuthorizationStateAfterTdlibParameters(TdApi.AuthorizationStateReady())
		Client.setCloseAuthorizationUpdateDelayMs(200L)
		Client.setChats(chat(11L, lastMessageDateSeconds = 1_700_000_003))
		val client = ReflectiveTelegramTdlibMessageClient(
			tdlibDirectory = testFolder.newFolder("tdlib"),
			apiId = 1,
			apiHash = "x",
			tdlibKeyProvider = StaticTelegramTdlibDatabaseKeyProvider(TDLIB_KEY),
			requestTimeoutMs = 1_000L,
		)

		val snapshot = TelegramMessageIngestDiagnostics(StubTelegramConnector(client))
			.readSnapshot(chatLimit = 1, messageLimit = 0)

		assertSnapshot(snapshot, TelegramMessageIngestStatus.CHAT_COUNT_ONLY, 1, 0)
		assertSentRequests(
			"SetTdlibParameters",
			"Close",
			"SetTdlibParameters",
			"GetChats",
			"GetChat",
			"Close",
		)
	}

	@Test
	fun testDiagnosticsIgnoresNonClosedAuthorizationUpdateBeforeReadCloseCompletes() {
		Client.resetTestState()
		Client.setAuthorizationStateAfterTdlibParameters(TdApi.AuthorizationStateReady())
		Client.setCloseAuthorizationStatePrelude(TdApi.AuthorizationStateReady())
		Client.setCloseAuthorizationUpdateDelayMs(200L)
		Client.setChats(chat(11L, lastMessageDateSeconds = 1_700_000_003))
		val client = ReflectiveTelegramTdlibMessageClient(
			tdlibDirectory = testFolder.newFolder("tdlib"),
			apiId = 1,
			apiHash = "x",
			tdlibKeyProvider = StaticTelegramTdlibDatabaseKeyProvider(TDLIB_KEY),
			requestTimeoutMs = 1_000L,
		)

		val snapshot = TelegramMessageIngestDiagnostics(StubTelegramConnector(client))
			.readSnapshot(chatLimit = 1, messageLimit = 0)

		assertSnapshot(snapshot, TelegramMessageIngestStatus.CHAT_COUNT_ONLY, 1, 0)
		assertSentRequests(
			"SetTdlibParameters",
			"Close",
			"SetTdlibParameters",
			"GetChats",
			"GetChat",
			"Close",
		)
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
		val client = ReflectiveTelegramTdlibMessageClient(
			tdlibDirectory = testFolder.newFolder("tdlib-concurrent"),
			apiId = 1,
			apiHash = "x",
			tdlibKeyProvider = StaticTelegramTdlibDatabaseKeyProvider(TDLIB_KEY),
			requestTimeoutMs = 2_000L,
		)
		val chatsResult = arrayOf<List<TelegramChat>?>(null)
		val chatsThread = Thread {
			chatsResult[0] = client.getRecentChats(1)
		}

		chatsThread.start()
		assertEquals(true, Client.awaitSetTdlibParametersAccepted(1_000L))
		val messages = client.getRecentMessages(11L, 1)
		chatsThread.join()

		assertEquals(
			listOf(TelegramChat(11L, "", 1_700_000_003, "preview", false)),
			chatsResult[0],
		)
		assertEquals(
			listOf(TelegramMessage(11L, 21L, 1_700_000_004, false, "body")),
			messages,
		)
	}

	@Test
	fun testMessageIngestDiagnosticsReturnsDisabledSnapshot() {
		val snapshot = TelegramMessageIngestDiagnostics(NoOpTelegramConnector())
			.readSnapshot(5, 5)

		assertSnapshot(snapshot, TelegramMessageIngestStatus.DISABLED, 0, 0)
	}

	@Test
	fun testMessageIngestDiagnosticsReportsAuthorizationUnavailable() {
		val snapshot = snapshotFor(FakeTelegramTdlibMessageClient(authorized = false))

		assertSnapshot(
			snapshot,
			TelegramMessageIngestStatus.AUTHORIZATION_UNAVAILABLE,
			0,
			0,
		)
	}

	@Test
	fun testMessageIngestDiagnosticsReportsCountsOnly() {
		val client = FakeTelegramTdlibMessageClient()
		val snapshot = snapshotFor(client)

		assertSnapshot(snapshot, TelegramMessageIngestStatus.MESSAGE_COUNT_AVAILABLE, 1, 1)
		assertEquals(Triple(3, 10L, 3), client.lastRequestLimits())
	}

	@Test
	fun testMessageIngestDiagnosticsTreatsAuthorizedEmptyAsNoContent() {
		val snapshot = snapshotFor(
			FakeTelegramTdlibMessageClient(
				chats = emptyList(),
				messages = emptyList(),
			),
		)

		assertSnapshot(snapshot, TelegramMessageIngestStatus.NO_CONTENT, 0, 0)
	}

	@Test
	fun testMessageIngestDiagnosticsSamplesMessagesAcrossChats() {
		val emptyChat = TelegramChat(1L, "", 0)
		val textChat = TelegramChat(10L, "", 1_700_000_000)
		val client = FakeTelegramTdlibMessageClient(
			chats = listOf(emptyChat, textChat),
			messages = emptyList(),
			messagesByChat = mapOf(
				textChat.id to listOf(
					TelegramMessage(10L, 20L, 1_700_000_001, false, ""),
				),
			),
		)

		val snapshot = snapshotFor(client, 2, 3)

		assertSnapshot(snapshot, TelegramMessageIngestStatus.MESSAGE_COUNT_AVAILABLE, 2, 1)
		// should call getRecentMessages for both chats
		assertEquals(setOf(emptyChat.id, textChat.id), client.messageChatIds.toSet())
	}

	@Test
	fun testMessageIngestDiagnosticsHonorsZeroMessageLimit() {
		val client = FakeTelegramTdlibMessageClient()
		val snapshot = snapshotFor(client, messageLimit = 0)

		assertSnapshot(snapshot, TelegramMessageIngestStatus.CHAT_COUNT_ONLY, 1, 0)
		assertEquals(emptyList<Long>(), client.messageChatIds)
	}

	private fun readyReflectiveMessageClient(
		configureClientState: () -> Unit = {},
	): ReflectiveTelegramTdlibMessageClient {
		Client.resetTestState()
		Client.setInitialAuthorizationState(TdApi.AuthorizationStateReady())
		configureClientState()
		return ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)
	}

	private class StaticTelegramTdlibDatabaseKeyProvider(private val key: ByteArray) :
		TelegramTdlibDatabaseKeyProvider {
		override fun getDatabaseEncryptionKey(tdlibDirectory: File): ByteArray = key.copyOf()
	}

	private fun snapshotFor(
		client: FakeTelegramTdlibMessageClient,
		chatLimit: Int = 3,
		messageLimit: Int = 3,
	) = TelegramMessageIngestDiagnostics(StubTelegramConnector(client))
		.readSnapshot(chatLimit, messageLimit)

	private fun assertSnapshot(
		snapshot: TelegramMessageIngestSnapshot,
		status: TelegramMessageIngestStatus,
		recentChatCount: Int,
		sampledMessageCount: Int,
	) {
		assertEquals(
			Triple(status, recentChatCount, sampledMessageCount),
			Triple(snapshot.status, snapshot.recentChatCount, snapshot.sampledMessageCount),
		)
	}

	private class FakeTelegramTdlibMessageClient(
		val authorized: Boolean = true,
		val chats: List<TelegramChat> = listOf(TelegramChat(10L, "", 1_700_000_000)),
		val messages: List<TelegramMessage> = listOf(
			TelegramMessage(10L, 20L, 1_700_000_001, false, ""),
		),
		val messagesByChat: Map<Long, List<TelegramMessage>> = emptyMap(),
	) : TelegramTdlibMessageClient {
		var lastChatLimit = 0
		var lastMessageChatId = 0L
		var lastMessageLimit = 0
		val messageChatIds = mutableListOf<Long>()

		override fun isAuthorized(): Boolean = authorized

		override fun getRecentChats(limit: Int): List<TelegramChat> {
			lastChatLimit = limit
			return chats
		}

		override fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage> {
			lastMessageChatId = chatId
			lastMessageLimit = limit
			messageChatIds.add(chatId)
			return messagesByChat.getOrDefault(chatId, messages)
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
