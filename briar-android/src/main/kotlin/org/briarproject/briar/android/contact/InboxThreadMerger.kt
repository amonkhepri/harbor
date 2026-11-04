package org.briarproject.briar.android.contact

object InboxThreadMerger {

	private val byLatestActivity = compareByDescending<InboxThreadItem> {
		it.latestActivityMillis
	}.thenBy {
		it.stableId
	}

	@JvmStatic
	@JvmOverloads
	fun merge(
		briarItems: List<ContactListItem>,
		connectorItems: List<ConnectorInboxThreadItem>,
		filter: InboxSourceFilter = InboxSourceFilter.All,
	): List<InboxThreadItem> {
		val items = ArrayList<InboxThreadItem>(
			briarItems.size + connectorItems.size,
		)
		if (filter == InboxSourceFilter.All || filter == InboxSourceFilter.Briar) {
			briarItems.mapTo(items, ::BriarInboxThreadItem)
		}
		if (filter == InboxSourceFilter.All) {
			items.addAll(connectorItems)
		} else if (filter != InboxSourceFilter.Briar) {
			connectorItems.filterTo(items) { filter.matches(it) }
		}
		items.sortWith(byLatestActivity)
		return items
	}
}
