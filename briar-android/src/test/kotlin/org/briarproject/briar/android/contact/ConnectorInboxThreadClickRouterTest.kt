package org.briarproject.briar.android.contact

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class ConnectorInboxThreadClickRouterTest {

	@Test
	fun testConnectorClickUsesFirstMatchingHandler() {
		val view = mock(View::class.java)
		val item = FakeConnectorInboxThreadItem()
		val ignoredHandler = RecordingConnectorClickHandler(false)
		val matchingHandler = RecordingConnectorClickHandler(true)
		val skippedHandler = RecordingConnectorClickHandler(true)

		ConnectorInboxThreadClickRouter(listOf(
			ignoredHandler,
			matchingHandler,
			skippedHandler
		)).onItemClick(view, item)

		assertEquals(listOf(view to item), ignoredHandler.clicks)
		assertEquals(listOf(view to item), matchingHandler.clicks)
		assertEquals(emptyList<Pair<View, ConnectorInboxThreadItem>>(), skippedHandler.clicks)
	}

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

	private class RecordingConnectorClickHandler(
		private val handled: Boolean,
	) : ConnectorInboxThreadClickHandler {
		val clicks = mutableListOf<Pair<View, ConnectorInboxThreadItem>>()

		override fun onConnectorItemClick(
			view: View,
			item: ConnectorInboxThreadItem,
		): Boolean {
			clicks += view to item
			return handled
		}
	}

}
