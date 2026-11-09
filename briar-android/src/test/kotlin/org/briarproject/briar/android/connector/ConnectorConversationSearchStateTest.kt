package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectorConversationSearchStateTest {

	@Test
	fun matchesAreCaseInsensitiveAndExcludeNonTextOrUnconfirmedRows() {
		val messages = listOf(
			item("1", "Hello World"),
			item("2", "no match here"),
			item("3", "hello again").copy(type = ConnectorMessageType.DELETED),
			item("4", "hello photo").copy(type = ConnectorMessageType.PHOTO),
			item("5", "hello pending").copy(localEchoStatus = ConnectorLocalEchoStatus.PENDING),
			item("6", "hello failed").copy(localEchoStatus = ConnectorLocalEchoStatus.FAILED),
			item("7", "hello sent").copy(localEchoStatus = ConnectorLocalEchoStatus.SENT),
		)
		val state = ConnectorConversationSearchState().withQuery("hello", messages)
		assertEquals(listOf(messages[0].stableId, messages[6].stableId), state.matchIds)
		assertEquals(messages[0].stableId, state.selectedId)
	}

	@Test
	fun blankQueryClearsSelectionAndUnmatchedQueryReportsNoMatches() {
		val messages = listOf(item("1", "hello"))
		val active = ConnectorConversationSearchState().withQuery("hello", messages)
		val blank = active.withQuery("", messages)
		assertEquals(false, blank.isActive)
		assertNull(blank.selectedId)

		val noMatch = ConnectorConversationSearchState().withQuery("xyz", messages)
		assertEquals(0, noMatch.matchCount)
		assertNull(noMatch.selectedId)
	}

	@Test
	fun navigationWrapsAroundInBothDirections() {
		val messages = listOf(item("1", "a"), item("2", "a"), item("3", "a"))
		val first = ConnectorConversationSearchState().withQuery("a", messages)
		assertEquals(messages[0].stableId, first.selectedId)
		assertEquals(messages[2].stableId, first.previous().selectedId)
		assertEquals(messages[1].stableId, first.previous().next().next().selectedId)
	}

	@Test
	fun refreshKeepsSelectionWhenStillMatchedAndFallsBackToFirstMatchOtherwise() {
		val messages = listOf(item("1", "a"), item("2", "a"))
		val selected = ConnectorConversationSearchState().withQuery("a", messages).next()
		assertEquals(messages[1].stableId, selected.selectedId)
		assertEquals(messages[1].stableId, selected.withMessages(messages.reversed()).selectedId)

		val deleted = messages[1].copy(type = ConnectorMessageType.DELETED)
		assertEquals(messages[0].stableId, selected.withMessages(listOf(messages[0], deleted)).selectedId)
	}

	@Test
	fun selectedRowIndexAccountsForDaySeparators() {
		val day = 86_400_000L
		val messages = listOf(
			item("1", "x").copy(dateMillis = day),
			item("2", "hit").copy(dateMillis = day + 1_000L),
			item("3", "hit").copy(dateMillis = 3 * day),
		)
		val rows = messages.toTimelineRows()
		val state = ConnectorConversationSearchState().withQuery("hit", messages)

		// Rows: sep, 1, 2, sep, 3 -- the message index would point at the wrong row.
		assertEquals(2, state.selectedRowIndex(rows))
		assertEquals(4, state.next().selectedRowIndex(rows))
		assertEquals(-1, ConnectorConversationSearchState().selectedRowIndex(rows))
		assertEquals(-1, state.selectedRowIndex(emptyList()))
	}

	private fun item(id: String, text: String) = ConnectorConversationMessageItem(
		ConnectorSources.MATRIX,
		"room",
		id,
		1_000L,
		false,
		text,
		ConnectorMessageType.TEXT,
	)
}
