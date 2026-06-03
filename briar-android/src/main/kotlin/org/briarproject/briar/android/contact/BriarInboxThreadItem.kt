package org.briarproject.briar.android.contact

class BriarInboxThreadItem(val item: ContactListItem) : InboxThreadItem {

	override val stableId: String
		get() = "briar:${item.contact.id.int}"

	override val latestActivityMillis: Long
		get() = item.timestamp

	override val source: InboxThreadItem.Source
		get() = InboxThreadItem.Source.BRIAR
}
