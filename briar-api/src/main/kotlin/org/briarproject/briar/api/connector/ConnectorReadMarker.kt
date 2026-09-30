package org.briarproject.briar.api.connector

/**
 * Opt-in write capability (MX-019): a connector that can acknowledge a thread as read at the
 * remote source implements this alongside [ReadOnlyConnector], instead of [ReadOnlyConnector]
 * itself forcing a read-marker method onto every connector. Telegram, Briar, and a disabled
 * connector placeholder need no change to stay without a read cursor.
 */
interface ConnectorReadMarker {
	/**
	 * Marks [threadId] read at the remote source. Returns true on success, false on failure --
	 * never throws -- so callers can retry silently without a checked exception boundary.
	 */
	fun markThreadRead(threadId: String): Boolean
}
