package org.briarproject.briar.api.matrix

import org.briarproject.briar.api.connector.ReadOnlyConnector

/** Matrix's [ReadOnlyConnector], mirroring `TelegramConnector`'s marker interface. */
interface MatrixConnector : ReadOnlyConnector {
	override fun isEnabled(): Boolean

	override fun isAuthorized(): Boolean
}
