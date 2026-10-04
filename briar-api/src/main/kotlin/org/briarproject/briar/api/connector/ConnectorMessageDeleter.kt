package org.briarproject.briar.api.connector

/**
 * Opt-in write capability (MX-020): a connector that can delete/redact an existing message
 * implements this alongside [ReadOnlyConnector].
 */
interface ConnectorMessageDeleter {
	/**
	 * Deletes [messageId] from [threadId]. Returns true on success, false on failure -- never throws.
	 */
	fun deleteMessage(threadId: String, messageId: String): Boolean
}
