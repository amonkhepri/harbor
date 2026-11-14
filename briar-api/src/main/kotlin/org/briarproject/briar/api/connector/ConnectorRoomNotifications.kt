package org.briarproject.briar.api.connector

/** Server-backed room preferences. Null means unavailable, never an inferred unmuted state. */
interface ConnectorRoomNotifications {
	fun prepareRoomNotifications(threadId: String): PreparedNotifications?

	interface PreparedNotifications {
		fun isCurrent(): Boolean
		fun readMuted(): Boolean?

		/** Returns the confirmed state after updating, or null on failure. */
		fun setMuted(muted: Boolean): Boolean?
	}
}
