package org.briarproject.briar.android.telegram;

import org.briarproject.briar.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelegramConversationActivityTest {

	@Test
	public void testEmptyTextDistinguishesDisabledUnauthorizedEmptyAndFailure() {
		assertEquals(R.string.telegram_conversation_loading,
				TelegramConversationActivity.emptyTextForState(true, true,
						false, true));
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

	@Test
	public void testTitleTextFallsBackForBlankTitles() {
		String fallback = "Telegram conversation";
		assertEquals(fallback,
				TelegramConversationActivity.titleText(null, fallback));
		assertEquals(fallback,
				TelegramConversationActivity.titleText("", fallback));
		assertEquals(fallback,
				TelegramConversationActivity.titleText(" \t\n ", fallback));
		assertEquals("Synthetic title",
				TelegramConversationActivity.titleText("Synthetic title",
						fallback));
	}

	@Test
	public void testDirectionTextDistinguishesIncomingAndOutgoingMessages() {
		assertEquals(R.string.telegram_conversation_direction_incoming,
				TelegramConversationAdapter.directionText(
						new TelegramConversationUiMessage(1L, 10L, false,
								"in")));
		assertEquals(R.string.telegram_conversation_direction_outgoing,
				TelegramConversationAdapter.directionText(
						new TelegramConversationUiMessage(2L, 20L, true,
								"out")));
	}
}
