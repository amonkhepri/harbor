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
		val states = listOf(
			ContactListViewModel.telegramAvailabilityStateFor(false, false, false, false),
			ContactListViewModel.telegramAvailabilityStateFor(true, false, false, false),
			ContactListViewModel.telegramAvailabilityStateFor(true, true, false, false),
			ContactListViewModel.telegramAvailabilityStateFor(true, true, true, false),
			ContactListViewModel.telegramAvailabilityStateFor(true, true, false, true)
		)
		assertEquals(listOf(NONE, ACCOUNT_UNAVAILABLE, EMPTY, LOAD_FAILED, NONE), states)
	}
}
