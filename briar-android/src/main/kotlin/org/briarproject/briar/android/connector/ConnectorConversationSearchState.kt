package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessageType

// MX-030: tracks matches by stable id, not list index, so a refresh/redaction can't select a stale row.
internal data class ConnectorConversationSearchState(
	val query: String = "",
	val matchIds: List<String> = emptyList(),
	val selectedId: String? = null,
) {
	val isActive: Boolean get() = query.isNotEmpty()
	val matchCount: Int get() = matchIds.size
	val selectedIndex: Int get() = matchIds.indexOf(selectedId)

	fun withQuery(
		query: String,
		messages: List<ConnectorConversationMessageItem>,
	): ConnectorConversationSearchState {
		if (query.isEmpty()) return ConnectorConversationSearchState()
		val matches = findMatches(messages, query)
		return ConnectorConversationSearchState(query, matches, matches.firstOrNull())
	}

	fun withMessages(
		messages: List<ConnectorConversationMessageItem>,
	): ConnectorConversationSearchState {
		if (!isActive) return this
		val matches = findMatches(messages, query)
		val selected = selectedId?.takeIf { it in matches } ?: matches.firstOrNull()
		return copy(matchIds = matches, selectedId = selected)
	}

	fun next(): ConnectorConversationSearchState = move(1)
	fun previous(): ConnectorConversationSearchState = move(-1)

	private fun move(delta: Int): ConnectorConversationSearchState {
		if (matchIds.isEmpty()) return this
		val current = matchIds.indexOf(selectedId)
		val wrapped = (if (current == -1) 0 else current + delta).mod(matchIds.size)
		return copy(selectedId = matchIds[wrapped])
	}

	companion object {
		fun findMatches(messages: List<ConnectorConversationMessageItem>, query: String): List<String> =
			messages
				.filter { it.isSearchableText() }
				.filter { it.text.contains(query, ignoreCase = true) }
				.map { it.stableId }

		private fun ConnectorConversationMessageItem.isSearchableText(): Boolean =
			type == ConnectorMessageType.TEXT &&
				localEchoStatus != ConnectorLocalEchoStatus.PENDING &&
				localEchoStatus != ConnectorLocalEchoStatus.FAILED
	}
}
