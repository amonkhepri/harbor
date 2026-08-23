package org.briarproject.briar.api.connector

data class ConnectorMessage(
	val source: ConnectorSource,
	val threadId: String,
	val messageId: String,
	val dateSeconds: Int,
	val isOutgoing: Boolean,
	val text: String,
	val sourceMessageOrder: Long,
	val type: ConnectorMessageType,
)

enum class ConnectorMessageType {
	TEXT,
	PHOTO,
	FILE,
}
