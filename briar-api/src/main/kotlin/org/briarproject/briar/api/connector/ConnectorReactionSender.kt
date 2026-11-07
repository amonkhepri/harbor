package org.briarproject.briar.api.connector

/**
 * Opt-in write capability (MX-059): a connector that can send a reaction to an existing message
 * implements this alongside [ReadOnlyConnector].
 */
interface ConnectorReactionSender {
	/**
	 * Sends [reaction] on [messageId] in [threadId]. Returns true once the provider accepted
	 * the reaction; false -- never throwing -- when the message cannot be found or the provider
	 * call fails.
	 */
	fun sendReaction(threadId: String, messageId: String, reaction: String): Boolean
}
