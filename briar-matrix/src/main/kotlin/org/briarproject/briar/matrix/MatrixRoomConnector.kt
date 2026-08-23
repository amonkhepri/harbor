package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.LoadFailed
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.Success
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
 * usable client is retained. [messageSnapshotClient] reads recent messages
 * for one room (MX-007 sub-slice 4): a `null` result (no session, room not
 * found, or timeline load failure) maps to [ConnectorMessageReadResult
 * .LoadFailed], and a list — including an empty one — maps to
 * [ConnectorMessageReadResult.Success]. Threads still report no known latest
 * message; wiring that read is a later, separately reviewed slice.
 */
class MatrixRoomConnector(
	private val roomSnapshotClient: MatrixRoomSnapshotClient,
	private val authSession: MatrixAuthSession,
	private val messageSnapshotClient: MatrixMessageSnapshotClient,
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

	override fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult {
		if (!isAuthorized()) return LoadFailed
		val snapshots = messageSnapshotClient.getRecentMessages(threadId, limit) ?: return LoadFailed
		return Success(
			snapshots.map { snapshot ->
				ConnectorMessage(
					source = source,
					threadId = threadId,
					messageId = snapshot.eventId,
					dateSeconds = snapshot.originServerTimestampSeconds
						.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
						.toInt(),
					isOutgoing = snapshot.isOutgoing,
					text = snapshot.bodyText,
					sourceMessageOrder = snapshot.originServerTimestampSeconds,
					type = ConnectorMessageType.TEXT,
				)
			},
		)
	}
}
