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

	private val genericConnectorSource = ConnectorSource("messenger", "Messenger")

	@Test
	fun testConnectorBadgeUsesSourceDisplayName() {
		val item = genericConnectorItem()

		assertEquals("Messenger", ConnectorInboxThreadViewHolder.sourceLabel(item))
	}

	@Test
	fun testConnectorSourceIcons() {
		fun assertSourceIcon(item: ConnectorInboxThreadItem, expectedIconRes: Int) {
			assertEquals(expectedIconRes, ConnectorInboxThreadViewHolder.sourceIconRes(item))
		}

		assertSourceIcon(
			FakeConnectorInboxThreadItem(connectorSource = ConnectorSources.TELEGRAM),
			R.drawable.ic_telegram
		)
		assertSourceIcon(genericConnectorItem(), R.drawable.ic_link_menu)
	}

	@Test
	fun testConnectorSourceDescriptionUsesSourceDisplayName() {
		val resources = mock(Resources::class.java)
		val item = genericConnectorItem()
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

	private fun genericConnectorItem() = FakeConnectorInboxThreadItem(
		connectorSource = genericConnectorSource,
	)

}
