package org.briarproject.briar.matrix

/**
 * Narrow, fakeable boundary over the joined-room listing on a Matrix SDK
 * client retained by a prior [MatrixLoginClient.login] or
 * [MatrixLoginClient.restore] (MX-006A). Kept isolated from [MatrixLoginClient]
 * so a room-listing regression can't block login/restore/logout.
 */
interface MatrixRoomSnapshotClient {
	/**
	 * Returns currently joined, non-space rooms in deterministic order, bounded
	 * to [limit]. Never throws; returns an empty list when no session is
	 * retained or room enumeration fails.
	 */
	fun getJoinedRooms(limit: Int): List<MatrixRoomSnapshot>
}

data class MatrixRoomSnapshot(val roomId: String, val displayName: String)
