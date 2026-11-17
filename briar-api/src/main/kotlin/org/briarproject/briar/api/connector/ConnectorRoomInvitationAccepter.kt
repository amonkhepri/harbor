package org.briarproject.briar.api.connector

/** Explicit invitation acceptance, bound to the session captured before confirmation. */
interface ConnectorRoomInvitationAccepter {
	fun prepareAcceptInvitation(threadId: String): PreparedAccept?

	interface PreparedAccept {
		/** Bounded blocking operation; call off the UI thread. Failures remain retryable. */
		fun accept(): Boolean
		fun isCurrent(): Boolean
	}
}
