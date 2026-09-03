package org.briarproject.briar.telegram

import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.telegram.TelegramConnector

class DisabledTelegramConnector : TelegramConnector {
	override val source: ConnectorSource = ConnectorSources.TELEGRAM

	override fun isEnabled(): Boolean = false

	override fun isAuthorized(): Boolean = false

	override fun getRecentThreads(limit: Int): List<ConnectorThread> = emptyList()

	override fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult =
		if (threadId.toLongOrNull() == null) {
			ConnectorMessageReadResult.LoadFailed
		} else {
			ConnectorMessageReadResult.Success(emptyList())
		}
}
