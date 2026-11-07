package org.briarproject.briar.api.connector

fun interface ConnectorThumbnailLoader {
	/**
	 * Loads a thumbnail for [attachment] in [threadId] / [messageId], bounded to
	 * [maxWidth] and [maxHeight] pixels.
	 *
	 * Returns raw thumbnail bytes, or null if unavailable, unauthorized, or loading fails.
	 */
	fun loadThumbnail(
		threadId: String,
		messageId: String,
		attachment: ConnectorAttachmentMetadata?,
		maxWidth: Int,
		maxHeight: Int,
	): ByteArray?
}
