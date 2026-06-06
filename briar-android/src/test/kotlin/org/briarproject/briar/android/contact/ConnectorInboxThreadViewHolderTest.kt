package org.briarproject.briar.android.contact

import android.content.res.Resources
import org.briarproject.briar.R
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ConnectorInboxThreadViewHolderTest {

	@Test
	fun testConnectorBadgeUsesSourceDisplayName() {
		val item = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSource("messenger", "Messenger"),
		)

		assertEquals("Messenger", ConnectorInboxThreadViewHolder.sourceLabel(item))
	}

	@Test
	fun testTelegramConnectorSourceUsesTelegramIcon() {
		val item = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSources.TELEGRAM,
		)

		assertEquals(
			R.drawable.ic_telegram,
			ConnectorInboxThreadViewHolder.sourceIconRes(item)
		)
	}

	@Test
	fun testUnknownConnectorSourceUsesGenericIcon() {
		val item = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSource("messenger", "Messenger"),
		)

		assertEquals(
			R.drawable.ic_link_menu,
			ConnectorInboxThreadViewHolder.sourceIconRes(item)
		)
	}

	@Test
	fun testConnectorSourceDescriptionUsesSourceDisplayName() {
		val resources = mock(Resources::class.java)
		val item = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSource("messenger", "Messenger"),
		)
		`when`(resources.getString(
			R.string.connector_thread_source_content_description,
			"Messenger"
		)).thenReturn("Messenger source")

		assertEquals("Messenger source",
			ConnectorInboxThreadViewHolder.sourceContentDescription(
				resources,
				item
			))
	}

}
