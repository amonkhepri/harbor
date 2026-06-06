package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSource

internal data class FakeConnectorInboxThreadItem(
	override val connectorSource: ConnectorSource = ConnectorSource("messenger", "Messenger"),
	override val connectorThreadId: String = "123",
	override val title: String = "synthetic title",
	override val latestActivityMillis: Long = 42L,
	override val previewText: String = "",
	override val isLastMessageOutgoing: Boolean = false,
	override val isPreviewLoading: Boolean = false,
) : ConnectorInboxThreadItem
