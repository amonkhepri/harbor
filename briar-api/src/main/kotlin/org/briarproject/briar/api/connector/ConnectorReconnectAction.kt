package org.briarproject.briar.api.connector

/**
 * Opt-in capability for connectors that support a connector-owned reconnect action
 * after session expiration while preserving non-secret navigation context.
 */
interface ConnectorReconnectAction {
	fun prepareReconnect(navigationContext: ConnectorNavigationContext? = null): PreparedReconnect?

	interface PreparedReconnect {
		val navigationContext: ConnectorNavigationContext?
		fun isCurrent(): Boolean
		fun reconnect(): Boolean
	}
}
