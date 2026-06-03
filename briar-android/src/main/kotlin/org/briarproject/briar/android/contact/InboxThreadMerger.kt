package org.briarproject.briar.android.contact

object InboxThreadMerger {

	private val byLatestActivity = compareByDescending<InboxThreadItem> {
		it.latestActivityMillis
	}.thenBy {
		it.stableId
	}

	@JvmStatic
	fun merge(
		briarItems: List<ContactListItem>,
		connectorItems: List<ConnectorInboxThreadItem>,
	): List<InboxThreadItem> {
		val items = ArrayList<InboxThreadItem>(
			briarItems.size + connectorItems.size
		)
		briarItems.mapTo(items) { BriarInboxThreadItem(it) }
		items.addAll(connectorItems)
		items.sortWith(byLatestActivity)
		return items
	}

	@JvmStatic
	fun sort(items: MutableList<InboxThreadItem>) {
		items.sortWith(byLatestActivity)
	}
}
