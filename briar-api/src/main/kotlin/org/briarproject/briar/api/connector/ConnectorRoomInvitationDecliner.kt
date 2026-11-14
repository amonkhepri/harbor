package org.briarproject.briar.api.connector

/** Explicit invitation rejection, bound to the session captured before confirmation. */
interface ConnectorRoomInvitationDecliner {
	fun prepareDeclineInvitation(threadId: String): PreparedDecline?

	interface PreparedDecline {
		/** Bounded blocking operation; call off the UI thread. Failures remain retryable. */
		fun decline(): Boolean
		fun isCurrent(): Boolean
	}
}
