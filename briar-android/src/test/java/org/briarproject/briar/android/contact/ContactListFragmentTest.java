package org.briarproject.briar.android.contact;

import org.briarproject.briar.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContactListFragmentTest {

	@Test
	public void testManualRefreshActionIsRecognized() {
		assertTrue(ContactListFragment.isManualRefreshAction(
				R.id.action_refresh_telegram_threads));
		assertFalse(ContactListFragment.isManualRefreshAction(
				android.R.id.home));
		assertFalse(ContactListFragment.isManualRefreshAction(
				R.id.action_add_contact_remotely));
	}

	@Test
	public void testTelegramAvailabilityUsesSanitizedEmptyText() {
		assertEquals(R.string.no_contacts,
				ContactListFragment.emptyTextForState(
						TelegramInboxAvailabilityState.NONE));
		assertEquals(R.string.telegram_inbox_loading,
				ContactListFragment.emptyTextForState(
						TelegramInboxAvailabilityState.LOADING));
		assertEquals(R.string.telegram_inbox_account_unavailable,
				ContactListFragment.emptyTextForState(
						TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE));
		assertEquals(R.string.telegram_inbox_empty,
				ContactListFragment.emptyTextForState(
						TelegramInboxAvailabilityState.EMPTY));
		assertEquals(R.string.telegram_inbox_load_failed,
				ContactListFragment.emptyTextForState(
						TelegramInboxAvailabilityState.LOAD_FAILED));
	}

	@Test
	public void testTelegramAvailabilityDoesNotAddEmptyAction() {
		assertEquals(R.string.no_contacts_action,
				ContactListFragment.emptyActionTextForState(
						TelegramInboxAvailabilityState.NONE));
		assertEquals(0, ContactListFragment.emptyActionTextForState(
				TelegramInboxAvailabilityState.LOADING));
		assertEquals(0, ContactListFragment.emptyActionTextForState(
				TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE));
		assertEquals(0, ContactListFragment.emptyActionTextForState(
				TelegramInboxAvailabilityState.EMPTY));
		assertEquals(0, ContactListFragment.emptyActionTextForState(
				TelegramInboxAvailabilityState.LOAD_FAILED));
	}
}
