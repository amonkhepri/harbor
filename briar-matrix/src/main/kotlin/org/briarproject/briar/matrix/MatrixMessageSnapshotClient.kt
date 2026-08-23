package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorMessageType

/**
 * Narrow, fakeable boundary over recent-message reading for one joined Matrix room on an SDK
 * client retained by a prior [MatrixLoginClient.login] or [MatrixLoginClient.restore], mirroring
 * [MatrixRoomSnapshotClient]'s isolation (MX-006A): a message-read regression can't block room
 * listing, login, restore, or logout.
 *
 * `docs/plans/mx007_m3_item2_timeline_scoping.md` records the pinned `sdk-android:26.08.05`
 * shape this boundary is designed against, verified over javap against the pinned AAR:
 * `Room.timeline(Continuation)` is a suspend function returning a `Timeline`, and `Timeline`
 * exposes no synchronous "current items" call, only `addListener(TimelineListener)`, whose
 * `onUpdate(List<TimelineDiff>)` delivers the initial snapshot asynchronously. The reflective
 * implementation bridges that the same way
 * `ReflectiveMatrixHomeserverDiscoveryClient` already bridges `Client.login`'s suspend
 * `Continuation` to a blocking call. `MatrixRoomConnector` consumes this interface for bounded
 * message reads; the reflective implementation may request one backward page before taking the
 * latest [limit] mapped messages.
 */
interface MatrixMessageSnapshotClient {
	/**
	 * Returns the most recent messages in [roomId], oldest-first, bounded to [limit]. Returns
	 * null, never throws, when no session is retained, [roomId] cannot be found, or timeline
	 * loading or backward pagination fails, so callers can distinguish "load failed" from
	 * "loaded, zero messages". A non-positive [limit] returns an empty list immediately.
	 */
	fun getRecentMessages(roomId: String, limit: Int): List<MatrixMessageSnapshot>?
}

/**
 * One Matrix room message mapped to plain fields so a connector can build a connector-neutral
 * message row without depending on raw Matrix SDK types.
 */
data class MatrixMessageSnapshot(
	val eventId: String,
	val senderId: String,
	val bodyText: String,
	val originServerTimestampSeconds: Long,
	val isOutgoing: Boolean,
	val type: ConnectorMessageType = ConnectorMessageType.TEXT,
)
