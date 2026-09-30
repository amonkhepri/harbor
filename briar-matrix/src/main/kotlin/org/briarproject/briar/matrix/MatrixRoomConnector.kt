package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorFileSender
import org.briarproject.briar.api.connector.ConnectorImageSender
import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.LoadFailed
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.Success
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReadMarker
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorTextSender
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
 * [ConnectorMessageReadResult.Success]. [getRecentThreads] wires each row's
 * latest-message preview from the same [messageSnapshotClient] (MX-007 M3
 * item 3 follow-up): a load failure or empty result leaves the row at its
 * prior no-known-latest-message defaults, so one room's message-read failure
 * cannot drop the room from the inbox or crash listing. [messageSendClient]
 * backs the opt-in [ConnectorTextSender] capability (MX-013), the opt-in [ConnectorImageSender]
 * capability (MX-014), and the opt-in [ConnectorReadMarker] capability (MX-019): all three write
 * capabilities fail closed, the same as reading, when not authorized.
 */
class MatrixRoomConnector(
	private val roomSnapshotClient: MatrixRoomSnapshotClient,
	private val authSession: MatrixAuthSession,
	private val messageSnapshotClient: MatrixMessageSnapshotClient,
	private val messageSendClient: MatrixMessageSendClient,
) : MatrixConnector,
	ConnectorTextSender,
	ConnectorImageSender,
	ConnectorFileSender,
	ConnectorReadMarker {
	override val source: ConnectorSource = ConnectorSources.MATRIX

	override fun isEnabled(): Boolean = true

	override fun isAuthorized(): Boolean = authSession.getSnapshot().authState == MatrixAuthState.READY

	override fun getRecentThreads(limit: Int): List<ConnectorThread> {
		if (!isAuthorized()) return emptyList()

		// Refreshes are disabled: MatrixConnector.SUPPORTS_BOUNDED_REFRESH is false.
		// No bounded SDK refresh exists using the verified sdk-android 26.08.05 call sequence.
		// Stopping here for scope splitting instead of inventing Fake/V2 Sync APIs.
		return roomSnapshotClient.getJoinedRooms(limit).map { snapshot ->
			val latest = messageSnapshotClient.getRecentMessages(snapshot.roomId, 1)?.lastOrNull()
			ConnectorThread(
				source = source,
				threadId = snapshot.roomId,
				title = snapshot.displayName,
				latestActivityDateSeconds = latest?.originServerTimestampSeconds
					?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
					?.toInt() ?: 0,
				latestMessageText = latest?.bodyText ?: "",
				isLatestMessageOutgoing = latest?.isOutgoing ?: false,
				latestMessageType = latest?.type ?: ConnectorMessageType.TEXT,
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
					type = snapshot.type,
					isEdited = snapshot.isEdited,
					isReply = snapshot.isReply,
					reactions = snapshot.reactions,
				)
			},
		)
	}

	override fun sendText(threadId: String, text: String): Boolean {
		if (!isAuthorized()) return false
		return messageSendClient.sendTextMessage(threadId, text)
	}

	override fun prepareFileSend(threadId: String): ConnectorFileSender.PreparedFileSend? {
		if (!isAuthorized()) return null
		val prepared = messageSendClient.prepareFileMessage(threadId) ?: return null
		return ConnectorFileSender.PreparedFileSend { bytes, name, mime ->
			isAuthorized() && prepared.send(bytes, name, mime)
		}
	}

	override fun sendImage(
		threadId: String,
		imageBytes: ByteArray,
		fileName: String,
		mimeType: String,
	): Boolean {
		if (!isAuthorized()) return false
		return messageSendClient.sendImageMessage(threadId, imageBytes, fileName, mimeType)
	}

	override fun markThreadRead(threadId: String): Boolean {
		if (!isAuthorized()) return false
		return messageSendClient.markRead(threadId)
	}
}
