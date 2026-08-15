package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.briarproject.briar.api.matrix.MatrixConnector

/**
 * Enabled Matrix [MatrixConnector] (MX-006A): maps joined, non-space rooms
 * from [roomSnapshotClient]'s retained/restored SDK client to
 * connector-neutral [ConnectorThread] rows. Authorization mirrors
 * [authSession]'s [MatrixAuthState.READY] state, the only state in which a
 * usable client is retained. Timeline loading is a later, separately
 * reviewed slice (see `docs/plans/autowork_task_backlog.md`), so every
 * thread reports no known latest message rather than reading one.
 */
class MatrixRoomConnector(
	private val roomSnapshotClient: MatrixRoomSnapshotClient,
	private val authSession: MatrixAuthSession,
) : MatrixConnector {
	override val source: ConnectorSource = ConnectorSources.MATRIX

	override fun isEnabled(): Boolean = true

	override fun isAuthorized(): Boolean = authSession.getSnapshot().authState == MatrixAuthState.READY

	override fun getRecentThreads(limit: Int): List<ConnectorThread> {
		if (!isAuthorized()) return emptyList()
		return roomSnapshotClient.getJoinedRooms(limit).map { snapshot ->
			ConnectorThread(
				source = source,
				threadId = snapshot.roomId,
				title = snapshot.displayName,
				latestActivityDateSeconds = 0,
				latestMessageText = "",
				isLatestMessageOutgoing = false,
				latestMessageType = ConnectorMessageType.TEXT,
			)
		}
	}

	override fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult =
		ConnectorMessageReadResult.Success(emptyList())
}
