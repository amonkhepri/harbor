package org.briarproject.briar.api.connector

/**
 * Non-secret navigation context retained across connector lifecycle/auth transitions
 * such as session expiration and reconnection. Contains only public identifiers and titles,
 * never credentials or auth tokens.
 */
data class ConnectorNavigationContext(
	val source: ConnectorSource,
	val threadId: String? = null,
	val threadTitle: String? = null,
)
