package org.briarproject.briar.android.contact

import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.TelegramInboxAvailabilityState.NONE
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactListViewModelTest {

	@Test
	fun testTelegramAvailabilityStateSelection() {
		assertEquals(NONE, ContactListViewModel.telegramAvailabilityStateFor(
			false, false, false, false))
		assertEquals(ACCOUNT_UNAVAILABLE,
			ContactListViewModel.telegramAvailabilityStateFor(
				true, false, false, false))
		assertEquals(EMPTY, ContactListViewModel.telegramAvailabilityStateFor(
			true, true, false, false))
		assertEquals(LOAD_FAILED,
			ContactListViewModel.telegramAvailabilityStateFor(
				true, true, true, false))
		assertEquals(NONE, ContactListViewModel.telegramAvailabilityStateFor(
			true, true, false, true))
	}
}
