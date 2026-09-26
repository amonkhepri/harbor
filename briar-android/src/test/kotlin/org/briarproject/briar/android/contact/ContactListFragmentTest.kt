package org.briarproject.briar.android.contact

import org.briarproject.briar.R
import org.briarproject.briar.android.connector.ConnectorConversationActivity
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOADING
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.NONE
import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = android.app.Application::class)
class ContactListFragmentTest {

	@Test
	fun testManualRefreshActionIsRecognized() {
		listOf(
			R.id.action_refresh_telegram_threads to true,
			android.R.id.home to false,
			R.id.action_add_contact_remotely to false,
		).forEach { (actionId, expected) ->
			assertEquals(expected, ContactListFragment.isManualRefreshAction(actionId))
		}
	}

	@Test
	fun testManualRefreshVisibilityHidesWhileLoading() {
		listOf(
			LOADING to false,
			NONE to true,
			EMPTY to true,
			ACCOUNT_UNAVAILABLE to true,
			LOAD_FAILED to true,
		).forEach { (state, expected) ->
			assertEquals(
				expected,
				ContactListFragment.shouldShowManualRefreshAction(
					true,
					state,
				),
			)
		}
		assertEquals(
			false,
			ContactListFragment.shouldShowManualRefreshAction(
				false,
				NONE,
			),
		)
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
		ConnectorInboxAvailabilityState.values().forEach { state ->
			val expected = if (state == NONE) R.string.no_contacts_action else 0
			assertEquals(expected, ContactListFragment.emptyActionTextForState(state))
		}
	}

	@Test
	fun routesMatrixInboxClickToConnectorConversationActivityWithRoomExtras() {
		val item = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			title = "Room title",
		)

		val intent = ContactListFragment.createConnectorConversationIntent(
			RuntimeEnvironment.getApplication(),
			item,
		)

		assertEquals(
			ConnectorConversationActivity::class.java.name,
			intent.component?.className,
		)
		assertEquals("matrix", intent.getStringExtra(ConnectorConversationActivity.SOURCE_ID))
		assertEquals(
			"!room:example.org",
			intent.getStringExtra(ConnectorConversationActivity.THREAD_ID),
		)
		assertEquals("Room title", intent.getStringExtra(ConnectorConversationActivity.THREAD_TITLE))
	}

	@Test
	fun routesTelegramInboxClickToConnectorConversationActivityWithChatExtras() {
		val item = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSources.TELEGRAM,
			connectorThreadId = "555",
			title = "Chat title",
		)

		val intent = ContactListFragment.createConnectorConversationIntent(
			RuntimeEnvironment.getApplication(),
			item,
		)

		assertEquals(
			ConnectorConversationActivity::class.java.name,
			intent.component?.className,
		)
		assertEquals("telegram", intent.getStringExtra(ConnectorConversationActivity.SOURCE_ID))
		assertEquals("555", intent.getStringExtra(ConnectorConversationActivity.THREAD_ID))
		assertEquals("Chat title", intent.getStringExtra(ConnectorConversationActivity.THREAD_TITLE))
	}
}
