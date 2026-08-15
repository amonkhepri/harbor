package org.briarproject.briar.android.contact

import org.briarproject.briar.R
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.LOADING
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.NONE
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactListFragmentTest {

	@Test
	fun testManualRefreshActionIsRecognized() {
		listOf(R.id.action_refresh_telegram_threads to true,
			android.R.id.home to false,
			R.id.action_add_contact_remotely to false).forEach { (actionId, expected) ->
			assertEquals(expected, ContactListFragment.isManualRefreshAction(actionId))
		}
	}

	@Test
	fun testManualRefreshVisibilityHidesWhileLoading() {
		listOf(LOADING to false, NONE to true, EMPTY to true,
			ACCOUNT_UNAVAILABLE to true, LOAD_FAILED to true).forEach { (state, expected) ->
			assertEquals(expected, ContactListFragment.shouldShowManualRefreshAction(
				true, state))
		}
		assertEquals(false, ContactListFragment.shouldShowManualRefreshAction(
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
