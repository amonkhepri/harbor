package org.briarproject.briar.api.connector

/** Opt-in manual unread capability; preparation captures a session without remote IO. */
interface ConnectorUnreadMarker {
	fun prepareMarkUnread(threadId: String): PreparedMarkUnread?

	interface PreparedMarkUnread {
		/** Bounded blocking operation, called off the UI thread. */
		fun markUnread(): Boolean
		fun isCurrent(): Boolean
	}
}
