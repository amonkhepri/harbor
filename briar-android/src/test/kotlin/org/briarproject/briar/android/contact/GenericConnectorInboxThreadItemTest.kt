package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.junit.Assert.assertEquals
import org.junit.Test

class GenericConnectorInboxThreadItemTest {

	@Test
	fun testMatrixRoomIdIsPreservedAsOpaqueString() {
		val item = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.MATRIX,
				"!room:matrix.example.org",
				"room",
				42,
				"synthetic\npreview\ttext",
				false,
				ConnectorMessageType.TEXT,
			),
		)

		assertEquals("!room:matrix.example.org", item.connectorThreadId)
		assertEquals(ConnectorSources.MATRIX, item.connectorSource)
		assertEquals("matrix:!room:matrix.example.org", item.stableId)
		assertEquals(42000L, item.latestActivityMillis)
		assertEquals("synthetic preview text", item.previewText)
	}

	@Test
	fun testTelegramAndMatrixRowsWithTheSameThreadIdHaveDistinctStableIds() {
		val telegram = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.TELEGRAM,
				"7",
				"chat",
				1,
				"",
				false,
				ConnectorMessageType.TEXT,
			),
		)
		val matrix = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.MATRIX,
				"7",
				"chat",
				1,
				"",
				false,
				ConnectorMessageType.TEXT,
			),
		)

		assertEquals("telegram:7", telegram.stableId)
		assertEquals("matrix:7", matrix.stableId)
	}

	@Test
	fun testOutgoingLoadingAndPhotoPreviewStateArePreserved() {
		val loading = GenericConnectorInboxThreadItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:matrix.example.org",
			title = "room",
			latestActivityMillis = 0L,
			previewText = "",
			isLastMessageOutgoing = false,
			isPreviewLoading = true,
			previewType = ConnectorMessageType.TEXT,
		)

		assertEquals(true, loading.isPreviewLoading)
		assertEquals(false, loading.hasPreviewText())

		val outgoingPhoto = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.MATRIX,
				"!room:matrix.example.org",
				"room",
				1,
				"photo",
				true,
				ConnectorMessageType.PHOTO,
			),
		)

		assertEquals(true, outgoingPhoto.isLastMessageOutgoing)
		assertEquals(ConnectorMessageType.PHOTO, outgoingPhoto.previewType)
	}
}
