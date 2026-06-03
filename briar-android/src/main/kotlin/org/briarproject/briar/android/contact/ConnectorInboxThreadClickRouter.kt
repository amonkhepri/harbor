package org.briarproject.briar.android.contact

import android.view.View

internal class ConnectorInboxThreadClickRouter(
	private val handlers: List<ConnectorInboxThreadClickHandler>,
) : OnContactClickListener<ConnectorInboxThreadItem> {

	constructor(listener: OnInboxThreadClickListener) : this(
		listOf(TelegramInboxThreadClickHandler(listener))
	)

	override fun onItemClick(view: View, item: ConnectorInboxThreadItem) {
		for (handler in handlers) {
			if (handler.onConnectorItemClick(view, item)) return
		}
	}
}

internal fun interface ConnectorInboxThreadClickHandler {

	fun onConnectorItemClick(
		view: View,
		item: ConnectorInboxThreadItem,
	): Boolean
}

private class TelegramInboxThreadClickHandler(
	private val listener: OnInboxThreadClickListener,
) : ConnectorInboxThreadClickHandler {

	override fun onConnectorItemClick(
		view: View,
		item: ConnectorInboxThreadItem,
	): Boolean {
		if (item !is TelegramInboxThreadItem) return false
		listener.onTelegramItemClick(view, item)
		return true
	}
}
