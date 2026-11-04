package org.briarproject.briar.api.connector

import java.io.Closeable

/**
 * Opt-in connector capability to observe incoming typing notifications for an open thread (MX-047).
 */
interface ConnectorTypingObserver {
	interface TypingSubscription : Closeable {
		fun cancel()
		override fun close()
	}

	/**
	 * Subscribes to typing notifications for [threadId].
	 * [onTypingChanged] is invoked with `true` when a peer is typing (excluding the current user),
	 * or `false` when no peer is typing.
	 * Returns null if observation is not supported or not authorized.
	 */
	fun observeTyping(
		threadId: String,
		onTypingChanged: (isTyping: Boolean) -> Unit,
	): TypingSubscription?
}
