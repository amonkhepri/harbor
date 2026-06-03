package org.briarproject.briar.api.telegram

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorSources

data class TelegramMessage(
	val chatId: Long,
	val messageId: Long,
	val dateSeconds: Int,
	val isOutgoing: Boolean,
	val text: String,
) {
	fun toConnectorMessage(): ConnectorMessage = ConnectorMessage(
		source = ConnectorSources.TELEGRAM,
		threadId = chatId.toString(),
		messageId = messageId.toString(),
		dateSeconds = dateSeconds,
		isOutgoing = isOutgoing,
		text = text,
		sourceMessageOrder = messageId,
	)
}
