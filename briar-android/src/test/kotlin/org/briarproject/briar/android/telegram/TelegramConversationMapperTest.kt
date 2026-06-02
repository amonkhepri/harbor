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
			TelegramMessage(1L, 3L, 30, true, "new"),
			TelegramMessage(1L, 2L, 20, false, ""),
			TelegramMessage(1L, 4L, 40, false, " \t\n"),
			TelegramMessage(1L, 1L, 10, false, "old"),
		))

		assertEquals(2, items.size)
		assertEquals("old", items[0].text)
		assertEquals(10000L, items[0].dateMillis)
		assertFalse(items[0].isOutgoing)
		assertEquals("new", items[1].text)
		assertEquals(30000L, items[1].dateMillis)
		assertTrue(items[1].isOutgoing)
	}
}
