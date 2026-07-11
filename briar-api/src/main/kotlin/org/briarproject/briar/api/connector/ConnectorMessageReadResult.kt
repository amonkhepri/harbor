package org.briarproject.briar.api.connector

sealed class ConnectorMessageReadResult {
	data class Success(val messages: List<ConnectorMessage>) : ConnectorMessageReadResult()

	object LoadFailed : ConnectorMessageReadResult()
}
