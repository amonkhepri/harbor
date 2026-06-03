package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSource

class BriarInboxThreadItem(val item: ContactListItem) : InboxThreadItem {

	override val stableId: String
		get() = "briar:${item.contact.id.int}"

	override val latestActivityMillis: Long
		get() = item.timestamp

	override val connectorSource: ConnectorSource?
		get() = null
}
