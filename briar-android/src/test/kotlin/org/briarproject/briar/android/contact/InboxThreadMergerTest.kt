package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.telegram.TelegramChat
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxThreadMergerTest {

	@Test
	fun testTelegramRowsMapSecondsToMillis() {
		val items = InboxThreadMerger.merge(
			emptyList(),
			listOf(TelegramInboxThreadItem(TelegramChat(7L, "chat", 42)))
		)

		val item = items[0] as TelegramInboxThreadItem
		val rowIdentity = Triple(item.chatId, item.title, item.latestActivityMillis)
		assertEquals(Triple(7L, "chat", 42000L), rowIdentity)
		assertPreviewState(item)
		assertEquals(
			ConnectorSources.TELEGRAM to "telegram:7",
			item.connectorSource to item.stableId
		)
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
		assertEquals(MESSENGER to "messenger:123", item.connectorSource to item.stableId)
	}

	@Test
	fun testTelegramRowsExposeCleanLatestPreview() {
		val item = TelegramInboxThreadItem(
			TelegramChat(7L, "chat", 42, "synthetic\npreview\ttext")
		)

		assertPreviewState(item, previewText = "synthetic preview text")
	}

	@Test
	fun testTelegramRowsExposeOutgoingLatestPreviewDirection() {
		val item = TelegramInboxThreadItem(
			TelegramChat(7L, "chat", 42, "synthetic\npreview\ttext", true)
		)

		assertPreviewState(
			item,
			previewText = "synthetic preview text",
			isLastMessageOutgoing = true,
		)
	}

	@Test
	fun testTelegramRowsExposeEmptyPreviewState() {
		val item = TelegramInboxThreadItem(TelegramChat(7L, "chat", 42))

		assertPreviewState(item)
	}

	@Test
	fun testTelegramRowsExposeLoadingState() {
		val item = TelegramInboxThreadItem(7L, "chat", 42000L)

		assertPreviewState(item, isPreviewLoading = true)
	}

	@Test
	fun testMixedItemsSortNewestFirst() {
		val items = mutableListOf<InboxThreadItem>(
			FakeInboxThreadItem("older", 1L, null),
			FakeInboxThreadItem("newer", 3L, ConnectorSources.TELEGRAM),
			FakeInboxThreadItem("middle", 2L, null),
		)

		InboxThreadMerger.sort(items)

		assertEquals(listOf("newer", "middle", "older"), items.map { it.stableId })
	}

	private class FakeInboxThreadItem(
		override val stableId: String,
		override val latestActivityMillis: Long,
		override val connectorSource: ConnectorSource?,
	) : InboxThreadItem

	private fun assertPreviewState(
		item: TelegramInboxThreadItem,
		previewText: String = "",
		isPreviewLoading: Boolean = false,
		isLastMessageOutgoing: Boolean = false,
	) {
		assertEquals(isPreviewLoading, item.isPreviewLoading)
		assertEquals(isLastMessageOutgoing, item.isLastMessageOutgoing)
		assertEquals(previewText.isNotEmpty(), item.hasPreviewText())
		assertEquals(previewText, item.previewText)
	}

	private companion object {
		val MESSENGER = ConnectorSource("messenger", "Messenger")
	}
}
