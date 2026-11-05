package org.briarproject.briar.api.connector

/**
 * Opt-in write capability (MX-057): a connector that can send a plain text message into an
 * already-joined thread as a reply to one existing message implements this alongside
 * [ConnectorTextSender]. Connectors without reply support keep sending ordinary text.
 */
interface ConnectorReplySender {
	/**
	 * Sends [text] into [threadId] as a reply to [replyToMessageId]. Returns true on success,
	 * false on failure -- never throws.
	 */
	fun sendReply(threadId: String, replyToMessageId: String, text: String): Boolean
}
