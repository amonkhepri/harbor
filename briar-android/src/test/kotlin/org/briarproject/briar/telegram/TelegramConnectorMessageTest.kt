package org.briarproject.briar.telegram

import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramMessage
import org.briarproject.briar.api.telegram.TelegramMessageIngestSnapshot
import org.briarproject.briar.api.telegram.TelegramMessageIngestStatus
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramConnectorMessageTest {

	@get:Rule
	val testFolder = TemporaryFolder()

	private fun assertSentRequests(vararg requestNames: String) {
		assertEquals(requestNames.toList(), Client.getSentRequestNames())
	}

	@Test
	fun testNoOpConnectorReturnsDisabledEmptyMessageLists() {
		val connector = NoOpTelegramConnector()

		assertFalse(connector.isEnabled())
		assertFalse(connector.isAuthorized())
		assertEquals(emptyList<TelegramChat>(), connector.getRecentChats(5))
		assertEquals(emptyList<TelegramMessage>(), connector.getRecentMessages(1L, 5))
	}

	@Test
	fun testEnabledConnectorDelegatesRecentChatsAndMessagesToClient() {
		val client = FakeTelegramTdlibMessageClient()
		val connector = StubTelegramConnector(client)

		assertTrue(connector.isEnabled())
		assertTrue(connector.isAuthorized())
		assertEquals(client.chats, connector.getRecentChats(3))
		assertEquals(3, client.lastChatLimit)
		assertEquals(client.messages, connector.getRecentMessages(10L, 2))
		assertEquals(10L, client.lastMessageChatId)
		assertEquals(2, client.lastMessageLimit)
	}

	@Test
	fun testEnabledConnectorExposesReadOnlyConnectorContract() {
		val client = FakeTelegramTdlibMessageClient(
				chats = listOf(TelegramChat(10L, "synthetic", 1_700_000_000,
						"preview", true)),
				messages = listOf(TelegramMessage(10L, 20L, 1_700_000_001,
						false, "body")),
		)
		val connector = StubTelegramConnector(client)

		assertEquals(ConnectorSources.TELEGRAM, connector.source)
		val threads = connector.getRecentThreads(3)
		assertEquals(ConnectorSources.TELEGRAM, threads[0].source)
		assertEquals("10", threads[0].threadId)
		assertEquals("synthetic", threads[0].title)
		assertEquals("preview", threads[0].latestMessageText)
		assertTrue(threads[0].isLatestMessageOutgoing)

		val messages = connector.getRecentMessages("10", 2)
		assertEquals(ConnectorSources.TELEGRAM, messages[0].source)
		assertEquals("10", messages[0].threadId)
		assertEquals("20", messages[0].messageId)
		assertEquals(20L, messages[0].sourceMessageOrder)
		assertEquals("body", messages[0].text)
	}

	@Test
	fun testReflectiveClientReturnsEmptyWhenAuthorizationIsNotReady() {
		Client.resetTestState()
		val client = ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)

		assertFalse(client.isAuthorized())
		assertEquals(emptyList<TelegramChat>(), client.getRecentChats(5))
		assertEquals(emptyList<TelegramMessage>(), client.getRecentMessages(10L, 5))
		assertFalse(Client.getSentRequestNames().contains("GetChats"))
		assertFalse(Client.getSentRequestNames().contains("GetChatHistory"))
	}

	@Test
	fun testReflectiveClientReportsAuthorizationReady() {
		val client = readyReflectiveMessageClient()

		assertTrue(client.isAuthorized())
		assertSentRequests("Close")
	}

	@Test
	fun testReflectiveClientMapsRecentChatsAndTextMessages() {
		val client = readyReflectiveMessageClient {
			Client.setChats(chat(
					10L,
					lastMessageDateSeconds = 1_700_000_000,
					body = "chat preview",
					isOutgoing = true,
			))
			Client.setMessages(
					10L,
					textMessage(10L, 20L, 1_700_000_001, isOutgoing = false),
					photoMessage(10L, 21L, 1_700_000_002),
			)
	}

		assertEquals(
				listOf(TelegramChat(10L, "", 1_700_000_000,
						"chat preview", true)),
				client.getRecentChats(3),
		)
		assertEquals(
				listOf(
						TelegramMessage(
								chatId = 10L,
								messageId = 20L,
								dateSeconds = 1_700_000_001,
								isOutgoing = false,
								text = "",
						),
				),
				client.getRecentMessages(10L, 3),
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
			Client.setMaxHistoryPageSize(2)
			Client.setMessages(
					10L,
					textMessage(10L, 40L, 1_700_000_004, isOutgoing = false, body = "latest"),
					textMessage(10L, 30L, 1_700_000_003, isOutgoing = false, body = "middle"),
					textMessage(10L, 20L, 1_700_000_002, isOutgoing = true, body = "older"),
					textMessage(10L, 10L, 1_700_000_001, isOutgoing = false, body = "oldest"),
			)
		}

		val messages = client.getRecentMessages(10L, 4)

		assertEquals(listOf(40L, 30L, 20L, 10L), messages.map { it.messageId })
		assertEquals(listOf("latest", "middle", "older", "oldest"), messages.map { it.text })
		assertSentRequests("GetChatHistory", "GetChatHistory", "GetChatHistory", "Close")
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
				requestTimeoutMs = 1_000L,
		)

		assertEquals(
				listOf(TelegramChat(11L, "", 1_700_000_003)),
				client.getRecentChats(1),
		)
		assertSentRequests("SetTdlibParameters", "GetChats", "GetChat", "Close")
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
		assertEquals(3, client.lastChatLimit)
		assertEquals(10L, client.lastMessageChatId)
		assertEquals(3, client.lastMessageLimit)
	}

	@Test
	fun testMessageIngestDiagnosticsTreatsAuthorizedEmptyAsNoContent() {
		val snapshot = snapshotFor(FakeTelegramTdlibMessageClient(
				chats = emptyList(),
				messages = emptyList(),
		))

		assertSnapshot(snapshot, TelegramMessageIngestStatus.NO_CONTENT, 0, 0)
	}

	@Test
	fun testMessageIngestDiagnosticsSamplesMessagesAcrossChats() {
		val emptyChat = TelegramChat(1L, "", 0)
		val textChat = TelegramChat(10L, "", 1_700_000_000)
		val client = FakeTelegramTdlibMessageClient(
				chats = listOf(emptyChat, textChat),
				messages = emptyList(),
				messagesByChat = mapOf(textChat.id to listOf(
						TelegramMessage(10L, 20L, 1_700_000_001, false, "")
				)),
		)

		val snapshot = snapshotFor(client, 2, 3)

		assertSnapshot(snapshot, TelegramMessageIngestStatus.MESSAGE_COUNT_AVAILABLE, 2, 1)
		// should call getRecentMessages for both chats
		assertTrue(client.messageChatIds.contains(emptyChat.id))
		assertTrue(client.messageChatIds.contains(textChat.id))
	}

	private fun readyReflectiveMessageClient(
		configureClientState: () -> Unit = {},
	): ReflectiveTelegramTdlibMessageClient {
		Client.resetTestState()
		Client.setInitialAuthorizationState(TdApi.AuthorizationStateReady())
		configureClientState()
		return ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)
	}

	private fun snapshotFor(
		client: FakeTelegramTdlibMessageClient,
		chatLimit: Int = 3,
		messageLimit: Int = 3,
	) =
		TelegramMessageIngestDiagnostics(StubTelegramConnector(client))
				.readSnapshot(chatLimit, messageLimit)

	private fun assertSnapshot(
		snapshot: TelegramMessageIngestSnapshot,
		status: TelegramMessageIngestStatus,
		recentChatCount: Int,
		sampledMessageCount: Int,
	) {
		assertEquals(status, snapshot.status)
		assertEquals(recentChatCount, snapshot.recentChatCount)
		assertEquals(sampledMessageCount, snapshot.sampledMessageCount)
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
	): TdApi.Chat =
		TdApi.Chat().also {
			it.id = id
			it.title = ""
			it.lastMessage = textMessage(id, id * 10, lastMessageDateSeconds,
					isOutgoing, body)
		}

	private fun textMessage(
		chatId: Long,
		messageId: Long,
		dateSeconds: Int,
		isOutgoing: Boolean,
		body: String = "",
	): TdApi.Message =
		TdApi.Message().also {
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

	private fun photoMessage(
		chatId: Long,
		messageId: Long,
		dateSeconds: Int,
	): TdApi.Message =
		TdApi.Message().also {
			it.chatId = chatId
			it.id = messageId
			it.date = dateSeconds
			it.content = TdApi.MessagePhoto()
		}
}
