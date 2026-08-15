package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSource

interface InboxThreadItem {
	val stableId: String

	val latestActivityMillis: Long

	val connectorSource: ConnectorSource?
}
