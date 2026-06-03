package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.telegram.TelegramChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxThreadMergerTest {

	@Test
	fun testTelegramRowsMapSecondsToMillis() {
		val items = InboxThreadMerger.merge(
			emptyList(),
			listOf(TelegramInboxThreadItem(TelegramChat(7L, "chat", 42)))
		)

		val item = items[0] as TelegramInboxThreadItem
		assertEquals(7L, item.chatId)
		assertEquals("chat", item.title)
		assertEquals(42000L, item.latestActivityMillis)
		assertFalse(item.isPreviewLoading)
		assertFalse(item.isLastMessageOutgoing)
		assertEquals("", item.previewText)
		assertEquals(ConnectorSources.TELEGRAM, item.connectorSource)
		assertEquals("telegram:7", item.stableId)
	}

	@Test
	fun testMergeAcceptsGenericConnectorRows() {
		val items = InboxThreadMerger.merge(
			emptyList(),
			listOf(FakeConnectorInboxThreadItem(
				connectorSource = MESSENGER,
				connectorThreadId = "123",
				latestActivityMillis = 7L,
			))
		)

		val item = items[0] as ConnectorInboxThreadItem
		assertEquals(MESSENGER, item.connectorSource)
		assertEquals("messenger:123", item.stableId)
	}

	@Test
	fun testTelegramRowsExposeCleanLatestPreview() {
		val item = TelegramInboxThreadItem(
			TelegramChat(7L, "chat", 42, "synthetic\npreview\ttext")
		)

		assertFalse(item.isPreviewLoading)
		assertFalse(item.isLastMessageOutgoing)
		assertTrue(item.hasPreviewText())
		assertEquals("synthetic preview text", item.previewText)
	}

	@Test
	fun testTelegramRowsExposeOutgoingLatestPreviewDirection() {
		val item = TelegramInboxThreadItem(
			TelegramChat(7L, "chat", 42, "synthetic\npreview\ttext", true)
		)

		assertFalse(item.isPreviewLoading)
		assertTrue(item.isLastMessageOutgoing)
		assertTrue(item.hasPreviewText())
		assertEquals("synthetic preview text", item.previewText)
	}

	@Test
	fun testTelegramRowsExposeEmptyPreviewState() {
		val item = TelegramInboxThreadItem(TelegramChat(7L, "chat", 42))

		assertFalse(item.isPreviewLoading)
		assertFalse(item.isLastMessageOutgoing)
		assertFalse(item.hasPreviewText())
		assertEquals("", item.previewText)
	}

	@Test
	fun testTelegramRowsExposeLoadingState() {
		val item = TelegramInboxThreadItem(7L, "chat", 42000L)

		assertTrue(item.isPreviewLoading)
		assertFalse(item.isLastMessageOutgoing)
		assertFalse(item.hasPreviewText())
		assertEquals("", item.previewText)
	}

	@Test
	fun testMixedItemsSortNewestFirst() {
		val items = mutableListOf<InboxThreadItem>(
			FakeInboxThreadItem("older", 1L, null),
			FakeInboxThreadItem("newer", 3L, ConnectorSources.TELEGRAM),
			FakeInboxThreadItem("middle", 2L, null),
		)

		InboxThreadMerger.sort(items)

		assertEquals("newer", items[0].stableId)
		assertEquals("middle", items[1].stableId)
		assertEquals("older", items[2].stableId)
	}

	private class FakeInboxThreadItem(
		override val stableId: String,
		override val latestActivityMillis: Long,
		override val connectorSource: ConnectorSource?,
	) : InboxThreadItem

	private data class FakeConnectorInboxThreadItem(
		override val connectorSource: ConnectorSource,
		override val connectorThreadId: String,
		override val latestActivityMillis: Long,
		override val title: String = "synthetic title",
		override val previewText: String = "",
		override val isLastMessageOutgoing: Boolean = false,
		override val isPreviewLoading: Boolean = false,
	) : ConnectorInboxThreadItem

	private companion object {
		val MESSENGER = ConnectorSource("messenger", "Messenger")
	}
}
