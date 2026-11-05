package org.briarproject.briar.api.connector

/**
 * Opt-in write capability (MX-058): a connector that can replace the text of one of the user's own
 * sent text messages implements this alongside [ReadOnlyConnector].
 */
interface ConnectorMessageEditor {
	/**
	 * Replaces the body of the user's own text message [messageId] in [threadId] with [text].
	 * Returns true once the provider accepted the edit; false -- never throwing -- when the message
	 * is not the user's own editable text message or the provider call fails.
	 */
	fun editTextMessage(threadId: String, messageId: String, text: String): Boolean
}
