package org.briarproject.briar.api.connector

data class ConnectorThread(
	val source: ConnectorSource,
	val threadId: String,
	val title: String,
	val latestActivityDateSeconds: Int,
	val latestMessageText: String = "",
	val isLatestMessageOutgoing: Boolean,
	val latestMessageType: ConnectorMessageType,
)
