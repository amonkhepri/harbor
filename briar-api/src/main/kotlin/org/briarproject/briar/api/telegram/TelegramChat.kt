package org.briarproject.briar.api.telegram

import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread

data class TelegramChat @JvmOverloads constructor(
	val id: Long,
	val title: String,
	val lastMessageDateSeconds: Int,
	val lastMessageText: String = "",
	val lastMessageIsOutgoing: Boolean = false,
) {
	fun toConnectorThread(): ConnectorThread = ConnectorThread(
		ConnectorSources.TELEGRAM,
		id.toString(),
		title,
		lastMessageDateSeconds,
		lastMessageText,
		lastMessageIsOutgoing
	)
}
