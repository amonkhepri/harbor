package org.briarproject.briar.api.matrix

import org.briarproject.briar.api.connector.ReadOnlyConnector

/** Matrix's [ReadOnlyConnector], mirroring `TelegramConnector`'s marker interface. */
interface MatrixConnector : ReadOnlyConnector {
	companion object {
		/**
		 * Constraint recorded per Class D Scope Decision (MX-011):
		 * No bounded SDK refresh exists using the verified sdk-android 26.08.05 call sequence.
		 * Fake or V2 sync APIs must not be invented. Refresh implementation is split and deferred.
		 */
		const val SUPPORTS_BOUNDED_REFRESH = false
	}

	override fun isEnabled(): Boolean

	override fun isAuthorized(): Boolean
}
