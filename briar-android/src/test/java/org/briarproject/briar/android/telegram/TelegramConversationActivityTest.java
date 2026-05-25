package org.briarproject.briar.android.telegram;

import org.briarproject.briar.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

	@Test
	public void testManualRefreshActionIsRecognized() {
		assertTrue(TelegramConversationActivity.isManualRefreshAction(
				R.id.action_refresh_telegram_conversation));
		assertFalse(TelegramConversationActivity.isManualRefreshAction(
				android.R.id.home));
		assertFalse(TelegramConversationActivity.isManualRefreshAction(
				R.id.action_delete_all_messages));
	}
}
