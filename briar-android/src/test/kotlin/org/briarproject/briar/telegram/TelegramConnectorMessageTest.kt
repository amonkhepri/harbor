package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramMessage
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

	@Test
	fun testNoOpConnectorReturnsDisabledEmptyMessageLists() {
		val connector = NoOpTelegramConnector()

		assertFalse(connector.isEnabled())
		assertEquals(emptyList<TelegramChat>(), connector.getRecentChats(5))
		assertEquals(emptyList<TelegramMessage>(), connector.getRecentMessages(1L, 5))
	}

	@Test
	fun testEnabledConnectorDelegatesRecentChatsAndMessagesToClient() {
		val client = FakeTelegramTdlibMessageClient()
		val connector = StubTelegramConnector(client)

		assertTrue(connector.isEnabled())
		assertEquals(client.chats, connector.getRecentChats(3))
		assertEquals(3, client.lastChatLimit)
		assertEquals(client.messages, connector.getRecentMessages(10L, 2))
		assertEquals(10L, client.lastMessageChatId)
		assertEquals(2, client.lastMessageLimit)
	}

	@Test
	fun testReflectiveClientReturnsEmptyWhenAuthorizationIsNotReady() {
		Client.resetTestState()
		val client = ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)

		assertEquals(emptyList<TelegramChat>(), client.getRecentChats(5))
		assertEquals(emptyList<TelegramMessage>(), client.getRecentMessages(10L, 5))
		assertFalse(Client.getSentRequestNames().contains("GetChats"))
		assertFalse(Client.getSentRequestNames().contains("GetChatHistory"))
	}

	@Test
	fun testReflectiveClientMapsRecentChatsAndTextMessages() {
		Client.resetTestState()
		Client.setInitialAuthorizationState(TdApi.AuthorizationStateReady())
		Client.setChats(chat(
				10L,
				lastMessageDateSeconds = 1_700_000_000,
				body = "chat preview",
		))
		Client.setMessages(
				10L,
				textMessage(10L, 20L, 1_700_000_001, isOutgoing = false),
				photoMessage(10L, 21L, 1_700_000_002),
		)
		val client = ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)

			assertEquals(
					listOf(TelegramChat(10L, "", 1_700_000_000, "chat preview")),
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
		assertEquals(
				listOf(
						"GetChats",
						"GetChat",
						"Close",
						"GetChatHistory",
						"GetChatHistory",
						"Close",
				),
				Client.getSentRequestNames(),
		)
	}

	@Test
	fun testReflectiveClientPaginatesPartialHistoryPages() {
		Client.resetTestState()
		Client.setInitialAuthorizationState(TdApi.AuthorizationStateReady())
		Client.setMaxHistoryPageSize(2)
		Client.setMessages(
				10L,
				textMessage(10L, 40L, 1_700_000_004, isOutgoing = false, body = "latest"),
				textMessage(10L, 30L, 1_700_000_003, isOutgoing = false, body = "middle"),
				textMessage(10L, 20L, 1_700_000_002, isOutgoing = true, body = "older"),
				textMessage(10L, 10L, 1_700_000_001, isOutgoing = false, body = "oldest"),
		)
		val client = ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)

		val messages = client.getRecentMessages(10L, 4)

		assertEquals(listOf(40L, 30L, 20L, 10L), messages.map { it.messageId })
		assertEquals(listOf("latest", "middle", "older", "oldest"), messages.map { it.text })
		assertEquals(
				listOf("GetChatHistory", "GetChatHistory", "GetChatHistory", "Close"),
				Client.getSentRequestNames(),
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
				requestTimeoutMs = 1_000L,
		)

		assertEquals(
				listOf(TelegramChat(11L, "", 1_700_000_003)),
				client.getRecentChats(1),
		)
		assertEquals(
				listOf("SetTdlibParameters", "GetChats", "GetChat", "Close"),
				Client.getSentRequestNames(),
		)
	}

	@Test
	fun testMessageIngestDiagnosticsReturnsDisabledSnapshot() {
		val snapshot = TelegramMessageIngestDiagnostics(NoOpTelegramConnector())
				.readSnapshot(chatLimit = 5, messageLimit = 5)

		assertEquals(TelegramMessageIngestStatus.DISABLED, snapshot.status)
		assertEquals(0, snapshot.recentChatCount)
		assertEquals(0, snapshot.sampledMessageCount)
	}

	@Test
	fun testMessageIngestDiagnosticsReportsCountsOnly() {
		val client = FakeTelegramTdlibMessageClient()
		val connector = StubTelegramConnector(client)

		val snapshot = TelegramMessageIngestDiagnostics(connector)
				.readSnapshot(chatLimit = 3, messageLimit = 3)

		assertEquals(TelegramMessageIngestStatus.MESSAGE_COUNT_AVAILABLE, snapshot.status)
		assertEquals(1, snapshot.recentChatCount)
		assertEquals(1, snapshot.sampledMessageCount)
		assertEquals(3, client.lastChatLimit)
		assertEquals(10L, client.lastMessageChatId)
		assertEquals(3, client.lastMessageLimit)
	}

	@Test
	fun testMessageIngestDiagnosticsTreatsNotReadyAsNoContent() {
		val connector = StubTelegramConnector(
				FakeTelegramTdlibMessageClient(
						chats = emptyList(),
						messages = emptyList(),
				),
		)

		val snapshot = TelegramMessageIngestDiagnostics(connector)
				.readSnapshot(chatLimit = 3, messageLimit = 3)

		assertEquals(TelegramMessageIngestStatus.NO_CONTENT, snapshot.status)
		assertEquals(0, snapshot.recentChatCount)
		assertEquals(0, snapshot.sampledMessageCount)
	}

	@Test
	fun testMessageIngestDiagnosticsSamplesMessagesAcrossChats() {
		class MultiChatClient(
			val chatList: List<TelegramChat>,
			val messagesByChat: Map<Long, List<TelegramMessage>>,
		) : TelegramTdlibMessageClient {
			val messageChatIds = mutableListOf<Long>()
			val messageLimits = mutableListOf<Int>()
			override fun getRecentChats(limit: Int): List<TelegramChat> =
				chatList.take(limit)
			override fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage> {
				messageChatIds.add(chatId)
				messageLimits.add(limit)
				return messagesByChat.getOrDefault(chatId, emptyList())
			}
		}

		val emptyChat = TelegramChat(1L, "", 0)
		val textChat = TelegramChat(10L, "", 1_700_000_000)
		val client = MultiChatClient(
				listOf(emptyChat, textChat),
				mapOf(textChat.id to listOf(
						TelegramMessage(10L, 20L, 1_700_000_001, false, "")
				)),
		)
		val connector = StubTelegramConnector(client)

		val snapshot = TelegramMessageIngestDiagnostics(connector)
				.readSnapshot(chatLimit = 2, messageLimit = 3)

		assertEquals(TelegramMessageIngestStatus.MESSAGE_COUNT_AVAILABLE, snapshot.status)
		assertEquals(2, snapshot.recentChatCount)
		assertEquals(1, snapshot.sampledMessageCount)
		// should call getRecentMessages for both chats
		assertTrue(client.messageChatIds.contains(emptyChat.id))
		assertTrue(client.messageChatIds.contains(textChat.id))
	}

	private class FakeTelegramTdlibMessageClient(
		val chats: List<TelegramChat> = listOf(TelegramChat(10L, "", 1_700_000_000)),
		val messages: List<TelegramMessage> = listOf(
				TelegramMessage(
						chatId = 10L,
						messageId = 20L,
						dateSeconds = 1_700_000_001,
						isOutgoing = false,
						text = "",
				),
		),
	) : TelegramTdlibMessageClient {
		var lastChatLimit = 0
		var lastMessageChatId = 0L
		var lastMessageLimit = 0

		override fun getRecentChats(limit: Int): List<TelegramChat> {
			lastChatLimit = limit
			return chats
		}

		override fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage> {
			lastMessageChatId = chatId
			lastMessageLimit = limit
			return messages
		}
	}

	private fun chat(
		id: Long,
		lastMessageDateSeconds: Int,
		body: String = "",
	): TdApi.Chat =
		TdApi.Chat().also {
			it.id = id
			it.title = ""
			it.lastMessage = textMessage(id, id * 10, lastMessageDateSeconds, false, body)
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
