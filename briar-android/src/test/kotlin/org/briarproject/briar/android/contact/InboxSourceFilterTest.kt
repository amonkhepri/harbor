package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxSourceFilterTest {

	private val matrixItem = FakeConnectorInboxThreadItem(
		connectorSource = ConnectorSources.MATRIX,
		connectorThreadId = "!room:example.org",
	)
	private val telegramItem = FakeConnectorInboxThreadItem(
		connectorSource = ConnectorSources.TELEGRAM,
		connectorThreadId = "123",
	)
	private val briarItem = FakeBriarInboxThreadItem(contactId = 42)

	@Test
	fun testAllFilterMatchesAnyItem() {
		assertTrue(InboxSourceFilter.All.matches(matrixItem))
		assertTrue(InboxSourceFilter.All.matches(telegramItem))
		assertTrue(InboxSourceFilter.All.matches(briarItem))
	}

	@Test
	fun testMatrixFilterMatchesOnlyMatrixItems() {
		assertTrue(InboxSourceFilter.Matrix.matches(matrixItem))
		assertFalse(InboxSourceFilter.Matrix.matches(telegramItem))
		assertFalse(InboxSourceFilter.Matrix.matches(briarItem))
	}

	@Test
	fun testBriarFilterMatchesOnlyBriarItems() {
		assertTrue(InboxSourceFilter.Briar.matches(briarItem))
		assertFalse(InboxSourceFilter.Briar.matches(matrixItem))
		assertFalse(InboxSourceFilter.Briar.matches(telegramItem))
	}

	@Test
	fun testTelegramFilterMatchesOnlyTelegramItems() {
		assertTrue(InboxSourceFilter.Telegram.matches(telegramItem))
		assertFalse(InboxSourceFilter.Telegram.matches(matrixItem))
		assertFalse(InboxSourceFilter.Telegram.matches(briarItem))
	}

	@Test
	fun testEmptyTextAndActionPreserveDefaultAndConnectorStates() {
		assertEquals(
			org.briarproject.briar.R.string.no_contacts,
			InboxSourceFilter.All.emptyText(ConnectorInboxAvailabilityState.NONE),
		)
		assertEquals(
			org.briarproject.briar.R.string.connector_inbox_loading,
			InboxSourceFilter.All.emptyText(ConnectorInboxAvailabilityState.LOADING),
		)
		assertEquals(
			org.briarproject.briar.R.string.no_contacts_action,
			InboxSourceFilter.All.emptyAction(ConnectorInboxAvailabilityState.NONE),
		)
		assertEquals(
			0,
			InboxSourceFilter.All.emptyAction(ConnectorInboxAvailabilityState.EMPTY),
		)

		assertEquals(
			org.briarproject.briar.R.string.connector_inbox_empty,
			InboxSourceFilter.Matrix.emptyText(ConnectorInboxAvailabilityState.NONE),
		)
		assertEquals(
			org.briarproject.briar.R.string.connector_inbox_loading,
			InboxSourceFilter.Matrix.emptyText(ConnectorInboxAvailabilityState.LOADING),
		)
		assertEquals(0, InboxSourceFilter.Matrix.emptyAction(ConnectorInboxAvailabilityState.NONE))

		assertEquals(
			org.briarproject.briar.R.string.no_contacts,
			InboxSourceFilter.Briar.emptyText(ConnectorInboxAvailabilityState.LOADING),
		)
		assertEquals(
			org.briarproject.briar.R.string.no_contacts_action,
			InboxSourceFilter.Briar.emptyAction(ConnectorInboxAvailabilityState.LOADING),
		)
	}

	private class FakeBriarInboxThreadItem(
		val contactId: Int,
		override val latestActivityMillis: Long = 1000L,
	) : InboxThreadItem {
		override val stableId: String = "briar:$contactId"
		override val connectorSource: org.briarproject.briar.api.connector.ConnectorSource? = null
	}
}
