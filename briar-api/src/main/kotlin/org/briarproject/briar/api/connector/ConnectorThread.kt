package org.briarproject.briar.api.connector

data class ConnectorThread(
	val source: ConnectorSource,
	val threadId: String,
	val title: String,
	val latestActivityDateSeconds: Int,
	val latestMessageText: String,
	val isLatestMessageOutgoing: Boolean,
	val latestMessageType: ConnectorMessageType,
	val unreadCount: Int? = null,
	val isMarkedUnread: Boolean? = null,
	val topic: String? = null,
	val isInvited: Boolean = false,
	/** True for a direct chat, false for a group room, null when unknown. */
	val isDirect: Boolean? = null,
)
