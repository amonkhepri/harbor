package org.briarproject.briar.android.contact

import android.view.View
import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class ConnectorInboxThreadClickRouterTest {

	@Test
	fun testTelegramConnectorClickForwardsToExistingCallback() {
		val listener = RecordingListener()
		val view = mock(View::class.java)
		val item = TelegramInboxThreadItem(7L, "synthetic title", 42L)

		ConnectorInboxThreadClickRouter(listener).onItemClick(view, item)

		assertEquals(0, listener.briarClickCount)
		assertEquals(1, listener.telegramClickCount)
		assertSame(view, listener.telegramView)
		assertSame(item, listener.telegramItem)
	}

	@Test
	fun testUnknownConnectorClickIsIgnored() {
		val listener = RecordingListener()
		val view = mock(View::class.java)
		val item = FakeConnectorInboxThreadItem()

		ConnectorInboxThreadClickRouter(listener).onItemClick(view, item)

		assertEquals(0, listener.briarClickCount)
		assertEquals(0, listener.telegramClickCount)
		assertNull(listener.telegramView)
		assertNull(listener.telegramItem)
	}

	private class RecordingListener : OnInboxThreadClickListener {
		var briarClickCount = 0
		var telegramClickCount = 0
		var telegramView: View? = null
		var telegramItem: TelegramInboxThreadItem? = null

		override fun onBriarItemClick(view: View, item: ContactListItem) {
			briarClickCount++
		}

		override fun onTelegramItemClick(
			view: View,
			item: TelegramInboxThreadItem,
		) {
			telegramClickCount++
			telegramView = view
			telegramItem = item
		}
	}

	private data class FakeConnectorInboxThreadItem(
		override val connectorSource: ConnectorSource = MESSENGER,
		override val connectorThreadId: String = "123",
		override val title: String = "synthetic title",
		override val latestActivityMillis: Long = 42L,
		override val previewText: String = "",
		override val isLastMessageOutgoing: Boolean = false,
		override val isPreviewLoading: Boolean = false,
	) : ConnectorInboxThreadItem

	private companion object {
		val MESSENGER = ConnectorSource("messenger", "Messenger")
	}
}
