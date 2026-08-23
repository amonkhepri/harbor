package org.briarproject.briar.matrix

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
 * implementation (a later, separately reviewed sub-slice) bridges that the same way
 * `ReflectiveMatrixHomeserverDiscoveryClient` already bridges `Client.login`'s suspend
 * `Continuation` to a blocking call. `MatrixRoomConnector` does not yet consume this interface;
 * wiring it in without a real implementation would trade its current honest "no known latest
 * message" stub for a misleading one.
 */
interface MatrixMessageSnapshotClient {
	/**
	 * Returns the most recent messages in [roomId], oldest-first, bounded to [limit]. Returns
	 * null, never throws, when no session is retained, [roomId] cannot be found, or timeline
	 * loading fails, so callers can distinguish "load failed" from "loaded, zero messages".
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
)
