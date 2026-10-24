package org.briarproject.briar.api.connector

interface ReadOnlyConnector {
	val source: ConnectorSource

	fun isEnabled(): Boolean

	fun isAuthorized(): Boolean

	fun getRecentThreads(limit: Int): List<ConnectorThread>

	/** Optional metadata lookup without loading other threads' timelines. */
	fun getThreadTopic(threadId: String): String? = null

	fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult
}
