package org.briarproject.briar.android.telegram;

import org.briarproject.briar.api.telegram.TelegramMessage;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelegramConversationMapperTest {

	@Test
	public void testTextMessagesRenderInDateOrderOnly() {
		List<TelegramConversationUiMessage> items =
				TelegramConversationMapper.toUiMessages(Arrays.asList(
						new TelegramMessage(1L, 3L, 30, true, "new"),
						new TelegramMessage(1L, 2L, 20, false, ""),
						new TelegramMessage(1L, 4L, 40, false, " \t\n"),
						new TelegramMessage(1L, 1L, 10, false, "old")
				));

		assertEquals(2, items.size());
		assertEquals("old", items.get(0).getText());
		assertEquals(10000L, items.get(0).getDateMillis());
		assertFalse(items.get(0).isOutgoing());
		assertEquals("new", items.get(1).getText());
		assertEquals(30000L, items.get(1).getDateMillis());
		assertTrue(items.get(1).isOutgoing());
	}
}
