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
		fun assertContentsChanged(changed: FakeConnectorInboxThreadItem) =
			assertFalse(callback.areContentsTheSame(item, changed))

		assertTrue(callback.areItemsTheSame(item, item.copy()))
		assertTrue(callback.areContentsTheSame(item, item.copy()))
		assertContentsChanged(item.copy(title = "new"))
		assertContentsChanged(item.copy(previewText = "new"))
		assertContentsChanged(item.copy(isLastMessageOutgoing = true))
		assertContentsChanged(item.copy(isPreviewLoading = true))
		assertContentsChanged(item.copy(latestActivityMillis = 2L))
	}

	private companion object {
		val MESSENGER = ConnectorSource("messenger", "Messenger")
	}
}
