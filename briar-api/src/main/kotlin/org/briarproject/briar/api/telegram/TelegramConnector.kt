package org.briarproject.briar.api.telegram

import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ReadOnlyConnector

interface TelegramConnector : ReadOnlyConnector {
	override val source: ConnectorSource
		get() = ConnectorSources.TELEGRAM

	override fun isEnabled(): Boolean
	override fun isAuthorized(): Boolean
}
