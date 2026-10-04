package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorFileSender.PreparedFileSend

/**
 * Narrow, fakeable boundary over sending one plain-text message or one image attachment into an
 * already-joined Matrix room on an SDK client retained by a prior [MatrixLoginClient.login] or
 * [MatrixLoginClient.restore] (MX-013 text, MX-014 image), mirroring [MatrixMessageSnapshotClient]'s
 * isolation: a send failure can't affect room listing or message reading.
 *
 * The pinned `sdk-android:26.08.05` shape, verified over javap against the pinned AAR:
 * `Timeline.createMessageContent(MessageType)` is a synchronous call that builds a
 * `RoomMessageEventContentWithoutRelation` from `MessageType.Text(TextMessageContent(body,
 * formatted))`, and the suspend `Timeline.send(content)` then fires it, returning an owned
 * `SendHandle`. Image attachments instead use the synchronous `Timeline.sendImage(UploadParameters,
 * UploadSource?, ImageInfo)`, which returns an owned `SendAttachmentJoinHandle`; its suspend
 * `join()` is awaited to learn success or failure. The reflective implementation bridges both
 * suspend calls the same way `ReflectiveMatrixHomeserverDiscoveryClient` already bridges
 * `Room.timeline()`. File attachments use `Timeline.sendFile(UploadParameters, FileInfo)`
 * and the same owned join handle (verified against the 26.08.05 AAR for MX-017). Marking a room
 * read instead uses the suspend `Room.markAsRead(ReceiptType)`, which returns `Unit` (verified
 * against the 26.08.05 AAR for MX-019).
 */
interface MatrixMessageSendClient {
	/** Captures the retained SDK session before provider IO; unsupported clients fail closed. */
	fun prepareFileMessage(roomId: String): PreparedFileSend? = null

	/**
	 * Sends [text] as a plain-text message into [roomId]. Returns true on success, false --
	 * never throwing -- when no session is retained, [roomId] cannot be found, or the SDK send
	 * call fails.
	 */
	fun sendTextMessage(roomId: String, text: String): Boolean

	/**
	 * Sends [imageBytes] as a single image attachment into [roomId] as [fileName] with content
	 * type [mimeType]. Returns true on success, false -- never throwing -- when no session is
	 * retained, [roomId] cannot be found, or the SDK send call fails.
	 */
	fun sendImageMessage(
		roomId: String,
		imageBytes: ByteArray,
		fileName: String,
		mimeType: String,
	): Boolean

	/**
	 * Marks [roomId] read with a public read receipt. Returns true on success, false -- never
	 * throwing -- when no session is retained, [roomId] cannot be found, or the SDK call fails.
	 */
	fun markRead(roomId: String): Boolean

	/**
	 * Deletes [eventId] from [roomId]. Returns true on success, false -- never
	 * throwing -- when no session is retained, [roomId] cannot be found, or the SDK call fails.
	 */
	fun deleteMessage(roomId: String, eventId: String): Boolean
}
