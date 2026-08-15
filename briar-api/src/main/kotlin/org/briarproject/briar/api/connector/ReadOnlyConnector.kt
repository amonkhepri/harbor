package org.briarproject.briar.api.connector

interface ReadOnlyConnector {
	fun isEnabled(): Boolean

	fun isAuthorized(): Boolean

	fun getRecentThreads(limit: Int): List<ConnectorThread>

	fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult
}
