package org.briarproject.briar.android.contact

import android.app.Application
import android.view.LayoutInflater
import android.widget.TextView
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider
import org.briarproject.briar.R
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
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
	fun testBlankTitleRowBindsFallbackAndPreservesPreviewState() {
		val item = GenericConnectorInboxThreadItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:matrix.example.org",
			title = " \t",
			latestActivityMillis = 0L,
			previewText = "",
			isLastMessageOutgoing = false,
			previewType = ConnectorMessageType.TEXT,
		)

		assertEquals(false, item.hasPreviewText())
		RuntimeEnvironment.getApplication().setTheme(R.style.BriarTheme)
		EmojiManager.install(GoogleEmojiProvider())
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_thread, null)
		val holder = ConnectorInboxThreadViewHolder(view)
		for (blankTitle in listOf(" \t", "", "   ", "\n\n")) {
			holder.bind(item.copy(title = blankTitle)) { _, _ -> }
			assertEquals("Chat", view.findViewById<TextView>(R.id.connectorThreadTitle).text.toString())
		}
		holder.bind(item.copy(title = "Custom Room")) { _, _ -> }
		assertEquals(
			"Custom Room",
			view.findViewById<TextView>(R.id.connectorThreadTitle).text.toString(),
		)

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

	@Test
	fun testUnreadCountCarriesThroughFromConnectorThread() {
		val withCount = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.MATRIX,
				"!room:matrix.example.org",
				"room",
				42,
				"preview",
				false,
				ConnectorMessageType.TEXT,
				unreadCount = 12,
				isMarkedUnread = true,
			),
		)
		assertEquals(12, withCount.unreadCount)
		assertEquals(true, withCount.isMarkedUnread)

		val withoutCount = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.MATRIX,
				"!room:matrix.example.org",
				"room",
				42,
				"preview",
				false,
				ConnectorMessageType.TEXT,
			),
		)
		assertEquals(null, withoutCount.unreadCount)
		assertEquals(null, withoutCount.isMarkedUnread)
	}
}
