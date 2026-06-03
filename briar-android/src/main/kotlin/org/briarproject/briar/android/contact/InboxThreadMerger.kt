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
		telegramItems: List<TelegramInboxThreadItem>,
	): List<InboxThreadItem> {
		val items = ArrayList<InboxThreadItem>(
			briarItems.size + telegramItems.size
		)
		briarItems.mapTo(items) { BriarInboxThreadItem(it) }
		items.addAll(telegramItems)
		items.sortWith(byLatestActivity)
		return items
	}

	@JvmStatic
	fun sort(items: MutableList<InboxThreadItem>) {
		items.sortWith(byLatestActivity)
	}
}
