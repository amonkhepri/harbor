package org.briarproject.briar.android.contact;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ContactListViewModelTest {

	@Test
	public void testTelegramAvailabilityStateSelection() {
		assertEquals(TelegramInboxAvailabilityState.NONE,
				ContactListViewModel.telegramAvailabilityStateFor(
						false, false, false, false));
		assertEquals(TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE,
				ContactListViewModel.telegramAvailabilityStateFor(
						true, false, false, false));
		assertEquals(TelegramInboxAvailabilityState.EMPTY,
				ContactListViewModel.telegramAvailabilityStateFor(
						true, true, false, false));
		assertEquals(TelegramInboxAvailabilityState.LOAD_FAILED,
				ContactListViewModel.telegramAvailabilityStateFor(
						true, true, true, false));
		assertEquals(TelegramInboxAvailabilityState.NONE,
				ContactListViewModel.telegramAvailabilityStateFor(
						true, true, false, true));
	}
}
