package org.briarproject.briar.android.contact

import org.briarproject.briar.R
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.LOADING
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactListFragmentTest {

	@Test
	fun testManualRefreshActionIsRecognized() {
		assertTrue(ContactListFragment.isManualRefreshAction(
			R.id.action_refresh_telegram_threads))
		assertFalse(ContactListFragment.isManualRefreshAction(android.R.id.home))
		assertFalse(ContactListFragment.isManualRefreshAction(
			R.id.action_add_contact_remotely))
	}

	@Test
	fun testManualRefreshVisibilityHidesWhileLoading() {
		assertFalse(ContactListFragment.shouldShowManualRefreshAction(
			true, LOADING))
		listOf(NONE, EMPTY, ACCOUNT_UNAVAILABLE, LOAD_FAILED).forEach { state ->
			assertTrue(ContactListFragment.shouldShowManualRefreshAction(
				true, state))
		}
		assertFalse(ContactListFragment.shouldShowManualRefreshAction(
			false, NONE))
	}

	@Test
	fun testTelegramAvailabilityUsesSanitizedEmptyText() {
		mapOf(
			NONE to R.string.no_contacts,
			LOADING to R.string.telegram_inbox_loading,
			ACCOUNT_UNAVAILABLE to R.string.telegram_inbox_account_unavailable,
			EMPTY to R.string.telegram_inbox_empty,
			LOAD_FAILED to R.string.telegram_inbox_load_failed,
		).forEach { (state, emptyText) ->
			assertEquals(emptyText, ContactListFragment.emptyTextForState(state))
		}
	}

	@Test
	fun testTelegramAvailabilityDoesNotAddEmptyAction() {
		TelegramInboxAvailabilityState.values().forEach { state ->
			val expected = if (state == NONE) R.string.no_contacts_action else 0
			assertEquals(expected, ContactListFragment.emptyActionTextForState(state))
		}
	}
}
