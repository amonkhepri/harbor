package org.briarproject.briar.android.contact

import android.view.MenuInflater
import android.widget.FrameLayout
import android.widget.PopupMenu
import org.briarproject.briar.R
import org.briarproject.briar.android.connector.ConnectorConversationActivity
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOADING
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.NONE
import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
			R.id.action_refresh_connector_threads to true,
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
			LOADING to R.string.connector_inbox_loading,
			ACCOUNT_UNAVAILABLE to R.string.connector_inbox_account_unavailable,
			EMPTY to R.string.connector_inbox_empty,
			LOAD_FAILED to R.string.connector_inbox_load_failed,
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
	fun testAggregateAvailabilityStatePrioritizesMostUrgentConnector() {
		// A Matrix-only problem must not be masked by an unrelated Telegram
		// state, in either priority direction.
		assertEquals(
			LOADING,
			ContactListFragment.aggregateAvailabilityState(
				mapOf(ConnectorSources.TELEGRAM to EMPTY, ConnectorSources.MATRIX to LOADING),
			),
		)
		assertEquals(
			ACCOUNT_UNAVAILABLE,
			ContactListFragment.aggregateAvailabilityState(
				mapOf(ConnectorSources.TELEGRAM to NONE, ConnectorSources.MATRIX to ACCOUNT_UNAVAILABLE),
			),
		)
		assertEquals(
			LOAD_FAILED,
			ContactListFragment.aggregateAvailabilityState(
				mapOf(
					ConnectorSources.TELEGRAM to ACCOUNT_UNAVAILABLE,
					ConnectorSources.MATRIX to LOAD_FAILED,
				),
			),
		)
		assertEquals(
			EMPTY,
			ContactListFragment.aggregateAvailabilityState(mapOf(ConnectorSources.MATRIX to EMPTY)),
		)
		assertEquals(
			NONE,
			ContactListFragment.aggregateAvailabilityState(emptyMap()),
		)
	}

	@Test
	fun testAggregateAvailabilityStateEmptyWinsOverNoneWithoutConsensus() {
		// This is a priority scan, not a consensus check: a single EMPTY
		// connector wins even though another enabled connector is NONE.
		assertEquals(
			EMPTY,
			ContactListFragment.aggregateAvailabilityState(
				mapOf(ConnectorSources.TELEGRAM to NONE, ConnectorSources.MATRIX to EMPTY),
			),
		)
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

	@Test
	fun testFilterActionRecognizedAndMapped() {
		assertEquals(InboxSourceFilter.All, ContactListFragment.filterForAction(R.id.action_filter_all))
		assertEquals(
			InboxSourceFilter.Matrix,
			ContactListFragment.filterForAction(R.id.action_filter_matrix),
		)
		assertEquals(
			InboxSourceFilter.Briar,
			ContactListFragment.filterForAction(R.id.action_filter_briar),
		)
		assertEquals(null, ContactListFragment.filterForAction(android.R.id.home))
		assertEquals(null, ContactListFragment.filterForAction(R.id.action_refresh_connector_threads))
	}

	@Test
	fun testFilterVisibilityRequiresEnabledConnector() {
		assertEquals(false, ContactListFragment.shouldShowFilterAction(false))
		assertEquals(true, ContactListFragment.shouldShowFilterAction(true))
	}

	@Test
	fun testOptionsMenuContainsFilterAndRefreshWithoutFabActions() {
		val context = RuntimeEnvironment.getApplication()
		val menu = PopupMenu(context, FrameLayout(context)).menu
		MenuInflater(context).inflate(R.menu.telegram_inbox_actions, menu)

		assertNotNull(menu.findItem(R.id.action_filter_inbox))
		assertNotNull(menu.findItem(R.id.action_refresh_connector_threads))
		assertNull(menu.findItem(R.id.action_add_contact_nearby))
		assertNull(menu.findItem(R.id.action_add_contact_remotely))

		val filterItem = menu.findItem(R.id.action_filter_inbox)
		val subMenu = filterItem.subMenu
		assertNotNull(subMenu)
		assertNotNull(subMenu!!.findItem(R.id.action_filter_all))
		assertNotNull(subMenu.findItem(R.id.action_filter_matrix))
		assertNotNull(subMenu.findItem(R.id.action_filter_briar))
	}

	@Test
	fun testFabSpeedDialMenuContainsOnlyAddContactActions() {
		val context = RuntimeEnvironment.getApplication()
		val menu = PopupMenu(context, FrameLayout(context)).menu
		MenuInflater(context).inflate(R.menu.contact_list_actions, menu)

		assertNull(menu.findItem(R.id.action_filter_inbox))
		assertNull(menu.findItem(R.id.action_refresh_connector_threads))
		assertNotNull(menu.findItem(R.id.action_add_contact_nearby))
		assertNotNull(menu.findItem(R.id.action_add_contact_remotely))
		assertEquals(2, menu.size())
	}

	@Test
	fun testFragmentOnCreateOptionsMenuInflatesOnlyFilterAndRefresh() {
		val context = RuntimeEnvironment.getApplication()
		val fragment = ContactListFragment()
		val menu = PopupMenu(context, FrameLayout(context)).menu
		fragment.onCreateOptionsMenu(menu, MenuInflater(context))

		assertNotNull(menu.findItem(R.id.action_filter_inbox))
		assertNotNull(menu.findItem(R.id.action_refresh_connector_threads))
		assertNull(menu.findItem(R.id.action_add_contact_nearby))
		assertNull(menu.findItem(R.id.action_add_contact_remotely))
	}
}
