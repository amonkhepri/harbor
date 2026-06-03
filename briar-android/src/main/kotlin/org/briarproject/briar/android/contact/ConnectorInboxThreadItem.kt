package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSource

interface ConnectorInboxThreadItem : InboxThreadItem {
	override val connectorSource: ConnectorSource

	val connectorThreadId: String

	val title: String

	val previewText: String

	val isLastMessageOutgoing: Boolean

	val isPreviewLoading: Boolean

	override val stableId: String
		get() = "${connectorSource.id}:$connectorThreadId"

	fun hasPreviewText(): Boolean = previewText.isNotEmpty()
}
