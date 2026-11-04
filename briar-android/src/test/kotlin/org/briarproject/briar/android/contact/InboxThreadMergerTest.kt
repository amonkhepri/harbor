package org.briarproject.briar.android.contact

import org.briarproject.bramble.test.TestUtils.getContact
import org.briarproject.briar.api.client.MessageTracker.GroupCount
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.identity.AuthorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxThreadMergerTest {

	@Test
	fun testMergeWrapsBriarContactsWithStableIdAndTimestamp() {
		val contact = getContact()
		val briarItem = ContactListItem(
			contact,
			AuthorInfo(AuthorInfo.Status.VERIFIED),
			true,
			GroupCount(1, 0, 5000L),
		)

		val items = InboxThreadMerger.merge(listOf(briarItem), listOf(connectorItem("c", 1L)))

		assertEquals(listOf("briar:${contact.id.int}", "m:c"), items.map { it.stableId })
		assertEquals(5000L, items[0].latestActivityMillis)
	}

	@Test
	fun testTelegramRowsMapSecondsToMillis() {
		val items = InboxThreadMerger.merge(emptyList(), listOf(telegramItem()))

		val item = items[0] as ConnectorInboxThreadItem
		val rowIdentity = Triple(item.connectorThreadId, item.title, item.latestActivityMillis)
		assertEquals(Triple("7", "chat", 42000L), rowIdentity)
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
	fun testMergeSortsTelegramAndMatrixRowsBySourceQualifiedStableId() {
		val telegram = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.TELEGRAM,
				"7",
				"chat",
				42,
				"",
				false,
				ConnectorMessageType.TEXT,
			),
		)
		val matrix = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.MATRIX,
				"!room:matrix.example.org",
				"room",
				42,
				"",
				false,
				ConnectorMessageType.TEXT,
			),
		)

		val items = InboxThreadMerger.merge(emptyList(), listOf(telegram, matrix))

		assertEquals(
			setOf("telegram:7", "matrix:!room:matrix.example.org"),
			items.map {
				it.stableId
			}.toSet(),
		)
	}

	@Test
	fun testMergeSortsNewestFirst() {
		val items = InboxThreadMerger.merge(
			emptyList(),
			listOf(connectorItem("older", 1L), connectorItem("newer", 3L), connectorItem("middle", 2L)),
		)

		assertEquals(listOf("m:newer", "m:middle", "m:older"), items.map { it.stableId })
	}

	@Test
	fun testSwitchingFromMatrixToAllRestoresRowsAndPreservesOrdering() {
		val contact = getContact()
		val briar = ContactListItem(
			contact,
			AuthorInfo(AuthorInfo.Status.VERIFIED),
			true,
			GroupCount(1, 0, 5000L),
		)
		val telegram = GenericConnectorInboxThreadItem(
			ConnectorThread(
				ConnectorSources.TELEGRAM,
				"7",
				"chat",
				42,
				"",
				false,
				ConnectorMessageType.TEXT,
			),
		)
		val matrix = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			latestActivityMillis = 10000L,
		)

		val matrixOnly = InboxThreadMerger.merge(
			listOf(briar),
			listOf(telegram, matrix),
			filter = InboxSourceFilter.Matrix,
		)
		assertEquals(listOf("matrix:!room:example.org"), matrixOnly.map { it.stableId })

		// Switching to All restores all rows and preserves newest-first ordering:
		// telegram: 42000ms, matrix: 10000ms, briar: 5000ms
		val all = InboxThreadMerger.merge(
			listOf(briar),
			listOf(telegram, matrix),
			filter = InboxSourceFilter.All,
		)
		assertEquals(
			listOf("telegram:7", "matrix:!room:example.org", "briar:${contact.id.int}"),
			all.map { it.stableId },
		)
	}

	@Test
	fun testBriarFilterExcludesConnectorRows() {
		val contact = getContact()
		val briar = ContactListItem(
			contact,
			AuthorInfo(AuthorInfo.Status.VERIFIED),
			true,
			GroupCount(1, 0, 5000L),
		)
		val matrix = FakeConnectorInboxThreadItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			latestActivityMillis = 10000L,
		)

		val items = InboxThreadMerger.merge(
			listOf(briar),
			listOf(matrix),
			filter = InboxSourceFilter.Briar,
		)
		assertEquals(listOf("briar:${contact.id.int}"), items.map { it.stableId })
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

	private fun telegramItem(text: String = "", outgoing: Boolean = false) =
		GenericConnectorInboxThreadItem(
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
		item: ConnectorInboxThreadItem,
		previewText: String = "",
		isLastMessageOutgoing: Boolean = false,
	) {
		assertEquals(
			listOf(isLastMessageOutgoing, previewText.isNotEmpty(), previewText),
			listOf(
				item.isLastMessageOutgoing,
				item.hasPreviewText(),
				item.previewText,
			),
		)
	}
}
