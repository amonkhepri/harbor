package org.briarproject.briar.api.connector

data class ConnectorSource(val id: String, val displayName: String)

object ConnectorSources {
	const val TELEGRAM_ID = "telegram"
	const val MATRIX_ID = "matrix"

	@JvmField
	val TELEGRAM = ConnectorSource(TELEGRAM_ID, "Telegram")

	@JvmField
	val MATRIX = ConnectorSource(MATRIX_ID, "Matrix")
}
