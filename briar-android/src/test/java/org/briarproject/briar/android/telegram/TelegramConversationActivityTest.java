package org.briarproject.briar.android.telegram;

import org.briarproject.briar.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TelegramConversationActivityTest {

	@Test
	public void testEmptyTextDistinguishesDisabledUnauthorizedEmptyAndFailure() {
		assertEquals(R.string.telegram_conversation_disabled,
				TelegramConversationActivity.emptyTextForState(false, false,
						false));
		assertEquals(R.string.telegram_conversation_account_unavailable,
				TelegramConversationActivity.emptyTextForState(true, false,
						false));
		assertEquals(R.string.telegram_conversation_load_failed,
				TelegramConversationActivity.emptyTextForState(true, true,
						true));
		assertEquals(R.string.telegram_conversation_empty,
				TelegramConversationActivity.emptyTextForState(true, true,
						false));
	}
}
