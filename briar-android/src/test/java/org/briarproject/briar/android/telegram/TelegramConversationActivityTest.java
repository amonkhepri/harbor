package org.briarproject.briar.android.telegram;

import org.briarproject.briar.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.briarproject.briar.android.util.UiUtils.formatDate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21)
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
	public void testManualRefreshVisibilityTracksConnectorState() {
		assertTrue(TelegramConversationActivity.shouldShowManualRefreshAction(
				true, 1L));
		assertTrue(TelegramConversationActivity.shouldShowManualRefreshAction(
				true, -1L));
		assertFalse(TelegramConversationActivity.shouldShowManualRefreshAction(
				true, 0L));
		assertFalse(TelegramConversationActivity.shouldShowManualRefreshAction(
				false, 1L));
	}

	@Test
	public void testChatIdGuardRejectsOnlyMissingDefault() {
		assertFalse(TelegramConversationActivity.hasValidChatId(0L));
		assertTrue(TelegramConversationActivity.hasValidChatId(1L));
		assertTrue(TelegramConversationActivity.hasValidChatId(-1L));
		assertEquals(R.string.telegram_conversation_load_failed,
				TelegramConversationActivity.emptyTextForState(true, true,
						true));
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

	@Test
	public void testDateTextHidesNonPositiveMessageDates() {
		TelegramConversationUiMessage missing =
				new TelegramConversationUiMessage(1L, 0L, false, "missing");
		TelegramConversationUiMessage invalid =
				new TelegramConversationUiMessage(2L, -1L, false, "invalid");
		TelegramConversationUiMessage valid =
				new TelegramConversationUiMessage(3L, 1_700_000_000_000L,
						false, "valid");

		assertEquals("",
				TelegramConversationAdapter.dateText(
						RuntimeEnvironment.getApplication(), missing));
		assertEquals("",
				TelegramConversationAdapter.dateText(
						RuntimeEnvironment.getApplication(), invalid));
		assertEquals(formatDate(RuntimeEnvironment.getApplication(),
						valid.getDateMillis()),
				TelegramConversationAdapter.dateText(
						RuntimeEnvironment.getApplication(), valid));
	}
}
