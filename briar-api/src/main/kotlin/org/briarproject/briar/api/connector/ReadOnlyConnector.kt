package org.briarproject.briar.api.connector

interface ReadOnlyConnector {
	val source: ConnectorSource

	fun isEnabled(): Boolean

	fun isAuthorized(): Boolean

	fun getRecentThreads(limit: Int): List<ConnectorThread>

	fun getRecentMessages(threadId: String, limit: Int): List<ConnectorMessage>
}
