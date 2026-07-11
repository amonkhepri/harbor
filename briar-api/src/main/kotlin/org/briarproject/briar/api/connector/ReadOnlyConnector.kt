package org.briarproject.briar.api.connector

interface ReadOnlyConnector {
	val source: ConnectorSource

	fun isEnabled(): Boolean

	fun isAuthorized(): Boolean

	fun getRecentThreads(limit: Int): List<ConnectorThread>

	fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult

	fun getRecentMessages(threadId: String, limit: Int): List<ConnectorMessage> =
		when (val result = getRecentMessageReadResult(threadId, limit)) {
			is ConnectorMessageReadResult.Success -> result.messages
			ConnectorMessageReadResult.LoadFailed -> emptyList()
		}
}
