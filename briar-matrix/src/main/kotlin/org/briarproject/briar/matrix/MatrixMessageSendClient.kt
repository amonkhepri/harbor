package org.briarproject.briar.matrix

/**
 * Narrow, fakeable boundary over sending one plain-text message into an already-joined Matrix
 * room on an SDK client retained by a prior [MatrixLoginClient.login] or
 * [MatrixLoginClient.restore] (MX-013), mirroring [MatrixMessageSnapshotClient]'s isolation: a
 * send failure can't affect room listing or message reading.
 *
 * The pinned `sdk-android:26.08.05` shape, verified over javap against the pinned AAR:
 * `Timeline.createMessageContent(MessageType)` is a synchronous call that builds a
 * `RoomMessageEventContentWithoutRelation` from `MessageType.Text(TextMessageContent(body,
 * formatted))`, and the suspend `Timeline.send(content)` then fires it, returning an owned
 * `SendHandle`. The reflective implementation bridges that suspend call the same way
 * `ReflectiveMatrixHomeserverDiscoveryClient` already bridges `Room.timeline()`.
 */
interface MatrixMessageSendClient {
	/**
	 * Sends [text] as a plain-text message into [roomId]. Returns true on success, false --
	 * never throwing -- when no session is retained, [roomId] cannot be found, or the SDK send
	 * call fails.
	 */
	fun sendTextMessage(roomId: String, text: String): Boolean
}
