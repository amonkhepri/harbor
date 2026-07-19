package org.briarproject.briar.api.telegram

import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ReadOnlyConnector

interface TelegramConnector : ReadOnlyConnector {
	override val source: ConnectorSource
		get() = ConnectorSources.TELEGRAM

	override fun isEnabled(): Boolean
	override fun isAuthorized(): Boolean
	fun getRecentMessageReadResult(chatId: Long, limit: Int): ConnectorMessageReadResult

	override fun getRecentMessageReadResult(
		threadId: String,
		limit: Int,
	): ConnectorMessageReadResult {
		val chatId = threadId.toLongOrNull() ?: return ConnectorMessageReadResult.LoadFailed
		return getRecentMessageReadResult(chatId, limit)
	}
}
