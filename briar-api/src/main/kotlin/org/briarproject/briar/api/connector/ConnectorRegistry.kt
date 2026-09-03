package org.briarproject.briar.api.connector

interface ConnectorRegistry {
	val connectors: Collection<ReadOnlyConnector>
}
