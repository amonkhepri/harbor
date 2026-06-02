package org.briarproject.briar.android.contact

import org.briarproject.briar.android.contact.InboxThreadItem.Source
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
		assertEquals(Source.TELEGRAM, item.source)
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
			FakeInboxThreadItem("older", 1L, Source.BRIAR),
			FakeInboxThreadItem("newer", 3L, Source.TELEGRAM),
			FakeInboxThreadItem("middle", 2L, Source.BRIAR),
		)

		InboxThreadMerger.sort(items)

		assertEquals("newer", items[0].stableId)
		assertEquals("middle", items[1].stableId)
		assertEquals("older", items[2].stableId)
	}

	private class FakeInboxThreadItem(
		private val stableId: String,
		private val latestActivityMillis: Long,
		private val source: Source,
	) : InboxThreadItem {

		override fun getStableId() = stableId

		override fun getLatestActivityMillis() = latestActivityMillis

		override fun getSource() = source
	}
}
