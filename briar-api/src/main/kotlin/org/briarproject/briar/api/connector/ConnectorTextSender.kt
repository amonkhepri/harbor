package org.briarproject.briar.api.connector

/**
 * Opt-in write capability (MX-013): a connector that can send a plain text message into an
 * already-joined thread implements this alongside [ReadOnlyConnector], instead of
 * [ReadOnlyConnector] itself declaring a forced send method. Telegram, Briar, and a disabled
 * connector placeholder need no change to stay read-only.
 */
interface ConnectorTextSender {
	/**
	 * Sends [text] into [threadId]. Returns true on success, false on failure -- never throws --
	 * so callers can show explicit sent/failed feedback without a checked exception boundary.
	 */
	fun sendText(threadId: String, text: String): Boolean
}
