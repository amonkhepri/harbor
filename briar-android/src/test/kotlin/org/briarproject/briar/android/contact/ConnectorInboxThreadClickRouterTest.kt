package org.briarproject.briar.android.contact

import android.view.View
import org.junit.Assert.assertEquals
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

		val handlerClicks = listOf(ignoredHandler.clicks, matchingHandler.clicks, skippedHandler.clicks)
		assertEquals(listOf(listOf(view to item), listOf(view to item), emptyList()), handlerClicks)
	}

	@Test
	fun testTelegramConnectorClickForwardsToExistingCallback() {
		val listener = RecordingListener()
		val view = mock(View::class.java)
		val item = TelegramInboxThreadItem(7L, "synthetic title", 42L)

		ConnectorInboxThreadClickRouter(listener).onItemClick(view, item)

		assertEquals(0 to 1, listener.briarAndTelegramClickCounts)
		assertEquals(view to item, listener.telegramForwardedClick)
	}

	@Test
	fun testUnknownConnectorClickIsIgnored() {
		val listener = RecordingListener()
		val view = mock(View::class.java)
		val item = FakeConnectorInboxThreadItem()

		ConnectorInboxThreadClickRouter(listener).onItemClick(view, item)

		assertEquals(0 to 0, listener.briarAndTelegramClickCounts)
		assertEquals(null to null, listener.telegramForwardedClick)
	}

	private class RecordingListener : OnInboxThreadClickListener {
		var briarClickCount = 0
		var telegramClickCount = 0
		val briarAndTelegramClickCounts get() = briarClickCount to telegramClickCount
		var telegramView: View? = null
		var telegramItem: TelegramInboxThreadItem? = null
		val telegramForwardedClick get() = telegramView to telegramItem

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
