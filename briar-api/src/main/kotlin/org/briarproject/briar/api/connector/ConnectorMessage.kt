package org.briarproject.briar.api.connector

data class ConnectorMessage @JvmOverloads constructor(
	val source: ConnectorSource,
	val threadId: String,
	val messageId: String,
	val dateSeconds: Int,
	val isOutgoing: Boolean,
	val text: String,
	val sourceMessageOrder: Long = 0L,
)
