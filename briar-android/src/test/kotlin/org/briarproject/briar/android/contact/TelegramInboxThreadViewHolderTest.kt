package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramInboxThreadViewHolderTest {

	@Test
	fun testConnectorBadgeUsesSourceDisplayName() {
		val item = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSource("messenger", "Messenger"),
		)

		assertEquals("Messenger", TelegramInboxThreadViewHolder.sourceLabel(item))
	}

	private data class FakeConnectorInboxThreadItem(
		override val connectorSource: ConnectorSource,
		override val connectorThreadId: String = "thread",
		override val title: String = "title",
		override val latestActivityMillis: Long = 1L,
		override val previewText: String = "",
		override val isLastMessageOutgoing: Boolean = false,
		override val isPreviewLoading: Boolean = false,
	) : ConnectorInboxThreadItem
}
