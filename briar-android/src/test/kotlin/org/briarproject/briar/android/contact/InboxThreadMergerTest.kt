package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxThreadMergerTest {

	@Test
	fun testTelegramRowsMapSecondsToMillis() {
		val items = InboxThreadMerger.merge(emptyList(), listOf(telegramItem()))

		val item = items[0] as TelegramInboxThreadItem
		val rowIdentity = Triple(item.chatId, item.title, item.latestActivityMillis)
		assertEquals(Triple(7L, "chat", 42000L), rowIdentity)
		assertPreviewState(item)
		assertEquals(ConnectorSources.TELEGRAM to "telegram:7", item.connectorSource to item.stableId)
	}

	@Test
	fun testTelegramRowsExposeCleanLatestPreview() {
		val item = telegramItem(text = "synthetic\npreview\ttext")

		assertPreviewState(item, previewText = "synthetic preview text")
	}

	@Test
	fun testTelegramRowsExposeOutgoingLatestPreviewDirection() {
		val item = telegramItem(text = "synthetic\npreview\ttext", outgoing = true)

		assertPreviewState(item, previewText = "synthetic preview text", isLastMessageOutgoing = true)
	}

	@Test
	fun testTelegramRowsExposeLoadingState() {
		val item = TelegramInboxThreadItem(7L, "chat", 42000L)

		assertPreviewState(item, isPreviewLoading = true)
	}

	@Test
	fun testMergeSortsNewestFirst() {
		val items = InboxThreadMerger.merge(
			emptyList(),
			listOf(connectorItem("older", 1L), connectorItem("newer", 3L), connectorItem("middle", 2L)),
		)

		assertEquals(listOf("m:newer", "m:middle", "m:older"), items.map { it.stableId })
	}

	private fun connectorItem(
		id: String,
		latestActivityMillis: Long,
		connectorSource: ConnectorSource = ConnectorSource("m", "Messenger"),
	) = FakeConnectorInboxThreadItem(
		connectorSource = connectorSource,
		connectorThreadId = id,
		latestActivityMillis = latestActivityMillis,
	)

	private fun telegramItem(text: String = "", outgoing: Boolean = false) = TelegramInboxThreadItem(
		ConnectorThread(
			ConnectorSources.TELEGRAM,
			"7",
			"chat",
			42,
			text,
			outgoing,
			ConnectorMessageType.TEXT,
		),
	)

	private fun assertPreviewState(
		item: TelegramInboxThreadItem,
		previewText: String = "",
		isPreviewLoading: Boolean = false,
		isLastMessageOutgoing: Boolean = false,
	) {
		assertEquals(
			listOf(isPreviewLoading, isLastMessageOutgoing, previewText.isNotEmpty(), previewText),
			listOf(
				item.isPreviewLoading,
				item.isLastMessageOutgoing,
				item.hasPreviewText(),
				item.previewText,
			),
		)
	}
}
