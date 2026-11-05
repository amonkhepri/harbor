package org.briarproject.briar.api.connector

fun interface ConnectorThumbnailLoader {
	/**
	 * Loads a thumbnail for [mediaUrl] or the message in [threadId] / [messageId], bounded to
	 * [maxWidth] and [maxHeight] pixels.
	 *
	 * Returns raw thumbnail bytes, or null if unavailable, unauthorized, or loading fails.
	 */
	fun loadThumbnail(
		threadId: String,
		messageId: String,
		mediaUrl: String?,
		maxWidth: Int,
		maxHeight: Int,
	): ByteArray?
}
