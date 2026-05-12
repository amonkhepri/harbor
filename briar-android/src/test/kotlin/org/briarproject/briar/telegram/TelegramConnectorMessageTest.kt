package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramMessage
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
		Client.setChats(chat(10L, lastMessageDateSeconds = 1_700_000_000))
		Client.setMessages(
				10L,
				textMessage(10L, 20L, 1_700_000_001, isOutgoing = false),
				photoMessage(10L, 21L, 1_700_000_002),
		)
		val client = ReflectiveTelegramTdlibMessageClient(requestTimeoutMs = 1_000L)

		assertEquals(
				listOf(TelegramChat(10L, "", 1_700_000_000)),
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
				listOf("GetChats", "GetChat", "Close", "GetChatHistory", "Close"),
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

	private class FakeTelegramTdlibMessageClient : TelegramTdlibMessageClient {
		val chats = listOf(TelegramChat(10L, "", 1_700_000_000))
		val messages = listOf(
			TelegramMessage(
				chatId = 10L,
				messageId = 20L,
				dateSeconds = 1_700_000_001,
				isOutgoing = false,
				text = "",
			),
		)
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

	private fun chat(id: Long, lastMessageDateSeconds: Int): TdApi.Chat =
		TdApi.Chat().also {
			it.id = id
			it.title = ""
			it.lastMessage = textMessage(id, id * 10, lastMessageDateSeconds, false)
		}

	private fun textMessage(
		chatId: Long,
		messageId: Long,
		dateSeconds: Int,
		isOutgoing: Boolean,
	): TdApi.Message =
		TdApi.Message().also {
			it.chatId = chatId
			it.id = messageId
			it.date = dateSeconds
			it.isOutgoing = isOutgoing
			it.content = TdApi.MessageText().also { content ->
				content.text = TdApi.FormattedText().also { text ->
					text.text = ""
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
