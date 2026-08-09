package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorThread

/**
 * Connector-neutral [ConnectorInboxThreadItem] backed by the string thread id
 * and source already carried by [ConnectorThread]. It makes no assumption
 * that the thread id is numeric, so it works for Telegram and connectors such
 * as Matrix whose room ids are opaque strings.
 */
data class GenericConnectorInboxThreadItem(
	override val connectorSource: ConnectorSource,
	override val connectorThreadId: String,
	override val title: String,
	override val latestActivityMillis: Long,
	override val previewText: String,
	override val isLastMessageOutgoing: Boolean,
	override val isPreviewLoading: Boolean,
	override val previewType: ConnectorMessageType,
) : ConnectorInboxThreadItem {

	constructor(thread: ConnectorThread) : this(
		thread.source,
		thread.threadId,
		thread.title,
		thread.latestActivityDateSeconds * 1000L,
		cleanPreviewText(thread.latestMessageText),
		thread.isLatestMessageOutgoing,
		false,
		thread.latestMessageType,
	)
}

internal fun cleanPreviewText(text: String): String = text.replace(Regex("\\s+"), " ").trim()
