package org.briarproject.briar.api.connector

/**
 * Opt-in write capability (MX-014): a connector that can send a single image attachment into an
 * already-joined thread implements this alongside [ReadOnlyConnector], instead of forcing an
 * attachment method onto [ConnectorTextSender] or [ReadOnlyConnector] itself. Telegram, Briar,
 * and a disabled connector placeholder need no change to stay read-only or text-only.
 */
interface ConnectorImageSender {
	companion object {
		// Bound memory used by the byte-array upload boundary, including provider streams of unknown size.
		const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
	}

	/**
	 * Sends the image content of [imageBytes] into [threadId] as [fileName] with content type
	 * [mimeType]. Returns true on success, false on failure -- never throws -- so callers can show
	 * explicit sent/failed feedback without a checked exception boundary.
	 */
	fun sendImage(threadId: String, imageBytes: ByteArray, fileName: String, mimeType: String): Boolean
}
