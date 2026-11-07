package org.briarproject.briar.api.connector

/**
 * Opt-in write capability (MX-060): a connector that can remove the user's own reaction
 * from an existing message implements this alongside [ReadOnlyConnector].
 */
interface ConnectorReactionRemover {
	/**
	 * Removes the user's own [reaction] on [messageId] in [threadId]. Returns true once the
	 * provider accepted the removal; false -- never throwing -- when the message cannot be
	 * found, the user did not own the reaction, or the provider call fails.
	 */
	fun removeReaction(threadId: String, messageId: String, reaction: String): Boolean
}
