package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramChat
import org.briarproject.briar.api.telegram.TelegramMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramConnectorMessageTest {

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
}
