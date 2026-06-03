package org.briarproject.briar.android.contact

import android.view.View

internal class ConnectorInboxThreadClickRouter(
	private val listener: OnInboxThreadClickListener,
) : OnContactClickListener<ConnectorInboxThreadItem> {

	override fun onItemClick(view: View, item: ConnectorInboxThreadItem) {
		if (item is TelegramInboxThreadItem) {
			listener.onTelegramItemClick(view, item)
		}
	}
}
