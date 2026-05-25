package org.briarproject.briar.android.telegram;

import org.briarproject.briar.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TelegramConversationActivityTest {

	@Test
	public void testEmptyTextDistinguishesDisabledEmptyAndFailure() {
		assertEquals(R.string.telegram_conversation_disabled,
				TelegramConversationActivity.emptyTextForState(false, false));
		assertEquals(R.string.telegram_conversation_load_failed,
				TelegramConversationActivity.emptyTextForState(true, true));
		assertEquals(R.string.telegram_conversation_empty,
				TelegramConversationActivity.emptyTextForState(true, false));
	}
}
