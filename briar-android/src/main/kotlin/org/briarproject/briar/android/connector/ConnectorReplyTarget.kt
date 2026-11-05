package org.briarproject.briar.android.connector

import android.content.res.Resources
import org.briarproject.briar.R
import org.briarproject.briar.api.connector.ConnectorMessageType

/**
 * The message selected for a composed reply (MX-057): its connector message id plus a bounded,
 * single-line preview shown above the composer. The preview is display-only and never logged.
 */
internal data class ConnectorReplyTarget(val messageId: String, val preview: String) {
	companion object {
		const val MAX_PREVIEW_CODE_POINTS = 80

		fun canReplyTo(item: ConnectorConversationMessageItem): Boolean = item.localEchoStatus == null &&
			item.type != ConnectorMessageType.DELETED &&
			item.connectorMessageId.isNotBlank()

		fun from(item: ConnectorConversationMessageItem, res: Resources): ConnectorReplyTarget {
			val text = when (item.type) {
				ConnectorMessageType.PHOTO -> res.getString(R.string.connector_message_photo)
				ConnectorMessageType.FILE -> ConnectorFileMetadataFormatter.sanitizeFileName(
					item.attachment?.fileName,
					res.getString(R.string.connector_message_file),
				)
				else -> item.text
			}
			return ConnectorReplyTarget(item.connectorMessageId, boundedPreview(text))
		}

		/** Collapses whitespace to one line and truncates to [MAX_PREVIEW_CODE_POINTS]. */
		fun boundedPreview(text: String): String {
			val singleLine = text.trim().replace(Regex("\\s+"), " ")
			val count = singleLine.codePointCount(0, singleLine.length)
			if (count <= MAX_PREVIEW_CODE_POINTS) return singleLine
			val end = singleLine.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS)
			return singleLine.substring(0, end) + "…"
		}
	}
}
