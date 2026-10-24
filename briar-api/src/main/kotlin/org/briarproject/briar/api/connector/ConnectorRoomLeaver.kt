package org.briarproject.briar.api.connector

/** Opt-in room departure; preparation captures the session without remote IO. */
interface ConnectorRoomLeaver {
	fun prepareLeave(threadId: String): PreparedLeave?

	interface PreparedLeave {
		/** Bounded blocking operation, called off the UI thread. */
		fun leave(): Boolean
		fun isCurrent(): Boolean
	}
}
