package org.briarproject.briar.api.connector

/** Optional, ephemeral typing capability. Never accepts composer content. */
interface ConnectorTypingSender {
	fun prepareTyping(threadId: String): PreparedTyping?

	interface PreparedTyping {
		fun isCurrent(): Boolean

		/** Non-blocking; recheck [wanted] immediately before starting queued work. */
		fun send(typing: Boolean, wanted: () -> Boolean)
	}
}
