package org.briarproject.briar.android.contact;

import org.briarproject.briar.R;
import org.junit.Test;

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
}
