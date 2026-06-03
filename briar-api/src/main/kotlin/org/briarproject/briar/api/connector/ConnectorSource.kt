package org.briarproject.briar.api.connector

data class ConnectorSource(
	val id: String,
	val displayName: String,
)

object ConnectorSources {
	const val TELEGRAM_ID = "telegram"

	@JvmField
	val TELEGRAM = ConnectorSource(TELEGRAM_ID, "Telegram")
}
