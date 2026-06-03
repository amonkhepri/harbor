package org.briarproject.briar.android.telegram

import org.briarproject.briar.api.telegram.TelegramMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramConversationMapperTest {

	@Test
	fun testTextMessagesRenderInDateOrderOnly() {
		val items = TelegramConversationMapper.toUiMessages(listOf(
			TelegramMessage(1L, 30L, 30, true, "new").toConnectorMessage(),
			TelegramMessage(1L, 2L, 20, false, "").toConnectorMessage(),
			TelegramMessage(1L, 4L, 40, false, " \t\n").toConnectorMessage(),
			TelegramMessage(1L, 1L, 10, false, "old").toConnectorMessage(),
			TelegramMessage(1L, 20L, 30, false, "same time").toConnectorMessage(),
		))

		assertEquals(3, items.size)
		assertEquals("telegram:1:1", items[0].stableId)
		assertEquals("telegram", items[0].connectorSource.id)
		assertEquals("1", items[0].connectorThreadId)
		assertEquals("1", items[0].connectorMessageId)
		assertEquals("old", items[0].text)
		assertEquals(10000L, items[0].dateMillis)
		assertFalse(items[0].isOutgoing)
		assertEquals("telegram:1:20", items[1].stableId)
		assertEquals("same time", items[1].text)
		assertEquals(30000L, items[1].dateMillis)
		assertFalse(items[1].isOutgoing)
		assertEquals("telegram:1:30", items[2].stableId)
		assertEquals("new", items[2].text)
		assertEquals(30000L, items[2].dateMillis)
		assertTrue(items[2].isOutgoing)
	}
}
