package org.briarproject.briar.android.connector

import android.content.Context
import android.content.res.Resources
import android.text.format.Formatter
import org.briarproject.briar.R

internal object ConnectorFileMetadataFormatter {

	const val MAX_FILENAME_LENGTH = 100
	private val MIME_TYPE_REGEX = Regex("^[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+$")

	fun sanitizeFileName(rawName: String?, fallback: String): String {
		if (rawName == null) return fallback
		val base = rawName.substringAfterLast('/').substringAfterLast('\\')
		val sanitized = buildString {
			var i = 0
			while (i < base.length) {
				val cp = base.codePointAt(i)
				i += Character.charCount(cp)
				if (!isDisallowedCodePoint(cp)) {
					appendCodePoint(cp)
				}
			}
		}.trim()

		if (sanitized.isEmpty() || isAllWhitespaceOrInvisible(sanitized)) {
			return fallback
		}

		return if (sanitized.length > MAX_FILENAME_LENGTH) {
			boundFileName(sanitized, MAX_FILENAME_LENGTH)
		} else {
			sanitized
		}
	}

	fun sanitizeMimeType(rawMime: String?): String? {
		val trimmed = rawMime?.trim() ?: return null
		return if (trimmed.matches(MIME_TYPE_REGEX)) trimmed else null
	}

	fun formatFileSize(context: Context, size: Long?): String? {
		if (size == null || size <= 0L) return null
		return Formatter.formatFileSize(context, size)
	}

	fun formatFileDetails(resources: Resources, mimeType: String?, formattedSize: String?): String =
		when {
			mimeType != null && formattedSize != null ->
				resources.getString(R.string.connector_file_details, mimeType, formattedSize)
			mimeType != null -> mimeType
			formattedSize != null -> formattedSize
			else -> ""
		}

	fun formatFileAccessibility(
		resources: Resources,
		fileName: String,
		mimeType: String?,
		formattedSize: String?,
		reactionSummary: String? = null,
	): String {
		val base = when {
			mimeType != null && formattedSize != null ->
				resources.getString(
					R.string.connector_file_accessibility_with_details,
					fileName,
					mimeType,
					formattedSize,
				)
			mimeType != null || formattedSize != null ->
				resources.getString(
					R.string.connector_file_accessibility,
					fileName,
					mimeType ?: formattedSize,
				)
			else ->
				resources.getString(R.string.connector_message_file) + ": " + fileName
		}
		return if (reactionSummary.isNullOrEmpty()) {
			base
		} else {
			resources.getString(
				R.string.connector_file_accessibility,
				base,
				reactionSummary,
			)
		}
	}

	private fun isDisallowedCodePoint(cp: Int): Boolean {
		// Control characters (C0, DEL, C1)
		if (cp < 0x20 || cp in 0x7F..0x9F) return true
		// Bidi controls and overrides (LRM, RLM, LRE, RLE, PDF, LRO, RLO, LRI, RLI, FSI, PDI)
		if (cp in 0x200E..0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069) return true
		// Zero-width and formatting invisibles (ZWSP, ZWNJ, ZWJ, WJ, BOM)
		if (cp in 0x200B..0x200D || cp == 0x2060 || cp == 0xFEFF) return true
		// Unpaired surrogates
		if (cp in 0xD800..0xDFFF) return true
		return false
	}

	private fun isAllWhitespaceOrInvisible(str: String): Boolean {
		var i = 0
		while (i < str.length) {
			val cp = str.codePointAt(i)
			i += Character.charCount(cp)
			if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !isDisallowedCodePoint(cp)) {
				return false
			}
		}
		return true
	}

	private fun boundFileName(name: String, maxLen: Int): String {
		val dotIndex = name.lastIndexOf('.')
		if (dotIndex in 1 until name.length - 1 && name.length - dotIndex <= 10) {
			val ext = name.substring(dotIndex)
			val available = maxLen - ext.length - 1
			if (available > 0) {
				return "${takeSafeChars(name, available)}…$ext"
			}
		}
		return "${takeSafeChars(name, maxLen - 1)}…"
	}

	private fun takeSafeChars(str: String, count: Int): String {
		if (count <= 0) return ""
		if (count >= str.length) return str
		val cut = if (Character.isHighSurrogate(str[count - 1])) {
			count - 1
		} else {
			count
		}
		return str.substring(0, cut)
	}
}
