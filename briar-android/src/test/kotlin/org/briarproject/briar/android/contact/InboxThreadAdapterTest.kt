package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxThreadAdapterTest {

	private val callback = InboxThreadAdapter.InboxThreadCallback()

	@Test
	fun testConnectorRowsUseGenericStableIds() {
		val item = FakeConnectorInboxThreadItem(
			connectorSource = MESSENGER,
			connectorThreadId = "123",
			title = "synthetic title",
			latestActivityMillis = 1L,
		)

		assertEquals("messenger:123", item.stableId)
	}

	@Test
	fun testConnectorContentsCompareGenericFields() {
		val item = FakeConnectorInboxThreadItem(
			connectorSource = MESSENGER,
			connectorThreadId = "123",
			title = "synthetic title",
			latestActivityMillis = 1L,
			previewText = "synthetic preview",
		)

		assertTrue(callback.areItemsTheSame(item, item.copy()))
		assertTrue(callback.areContentsTheSame(item, item.copy()))
		assertFalse(callback.areContentsTheSame(item, item.copy(title = "new")))
		assertFalse(callback.areContentsTheSame(item, item.copy(
			previewText = "new"
		)))
		assertFalse(callback.areContentsTheSame(item, item.copy(
			isLastMessageOutgoing = true
		)))
		assertFalse(callback.areContentsTheSame(item, item.copy(
			isPreviewLoading = true
		)))
		assertFalse(callback.areContentsTheSame(item, item.copy(
			latestActivityMillis = 2L
		)))
	}

	private companion object {
		val MESSENGER = ConnectorSource("messenger", "Messenger")
	}
}
