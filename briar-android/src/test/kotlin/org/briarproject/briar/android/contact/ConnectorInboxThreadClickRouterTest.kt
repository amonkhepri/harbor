package org.briarproject.briar.android.contact

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class ConnectorInboxThreadClickRouterTest {

	@Test
	fun testTelegramConnectorClickForwardsToExistingCallback() {
		val listener = RecordingListener()
		val view = mock(View::class.java)
		val item = TelegramInboxThreadItem(7L, "synthetic title", 42L)

		ConnectorInboxThreadClickRouter(listener).onItemClick(view, item)

		assertEquals((0 to 1) to (view to item), listener.callbackState)
	}

	@Test
	fun testUnknownConnectorClickIsIgnored() {
		val listener = RecordingListener()
		val view = mock(View::class.java)
		val item = FakeConnectorInboxThreadItem()

		ConnectorInboxThreadClickRouter(listener).onItemClick(view, item)

		assertEquals((0 to 0) to (null to null), listener.callbackState)
	}

	private class RecordingListener : OnInboxThreadClickListener {
		var briarClickCount = 0
		var telegramClickCount = 0
		val briarAndTelegramClickCounts get() = briarClickCount to telegramClickCount
		var telegramView: View? = null
		var telegramItem: TelegramInboxThreadItem? = null
		val telegramForwardedClick get() = telegramView to telegramItem
		val callbackState get() = briarAndTelegramClickCounts to telegramForwardedClick

		override fun onBriarItemClick(view: View, item: ContactListItem) {
			briarClickCount++
		}

		override fun onTelegramItemClick(view: View, item: TelegramInboxThreadItem) {
			telegramClickCount++
			telegramView = view
			telegramItem = item
		}
	}
}
