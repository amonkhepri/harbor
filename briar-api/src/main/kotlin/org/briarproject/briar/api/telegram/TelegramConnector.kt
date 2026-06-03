package org.briarproject.briar.api.telegram

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector

interface TelegramConnector : ReadOnlyConnector {
	override val source: ConnectorSource
		get() = ConnectorSources.TELEGRAM

	override fun isEnabled(): Boolean
	override fun isAuthorized(): Boolean
	fun getRecentChats(limit: Int): List<TelegramChat>
	fun getRecentMessages(chatId: Long, limit: Int): List<TelegramMessage>

	override fun getRecentThreads(limit: Int): List<ConnectorThread> =
		getRecentChats(limit).map { it.toConnectorThread() }

	override fun getRecentMessages(
		threadId: String,
		limit: Int,
	): List<ConnectorMessage> =
		getRecentMessages(threadId.toLong(), limit)
			.map { it.toConnectorMessage() }
}
