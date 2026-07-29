package org.briarproject.briar.api.telegram

import org.briarproject.briar.api.connector.ReadOnlyConnector

interface TelegramConnector : ReadOnlyConnector {
	override fun isEnabled(): Boolean
	override fun isAuthorized(): Boolean
}
