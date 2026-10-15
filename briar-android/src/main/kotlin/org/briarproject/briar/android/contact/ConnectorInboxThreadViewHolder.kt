package org.briarproject.briar.android.contact

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.UiThread
import androidx.recyclerview.widget.RecyclerView
import org.briarproject.briar.R
import org.briarproject.briar.android.util.UiUtils.formatDate
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorMessageType

@UiThread
class ConnectorInboxThreadViewHolder(view: View) : RecyclerView.ViewHolder(view) {
	private val sourceIcon: ImageView = view.findViewById(R.id.connectorThreadIcon)
	private val title: TextView = view.findViewById(R.id.connectorThreadTitle)
	private val date: TextView = view.findViewById(R.id.connectorThreadDate)
	private val badge: TextView = view.findViewById(R.id.connectorThreadBadge)
	private val preview: TextView = view.findViewById(R.id.connectorThreadPreview)

	fun bind(
		item: ConnectorInboxThreadItem,
		listener: OnContactClickListener<ConnectorInboxThreadItem>,
	) {
		title.text = titleText(title.resources, item)
		date.visibility = dateVisibility(item)
		date.text = dateText(date.context, item)
		sourceIcon.setImageResource(sourceIconRes(item))
		sourceIcon.contentDescription =
			sourceContentDescription(sourceIcon.resources, item)
		badge.text = sourceLabel(item)
		preview.text = when {
			item.isPreviewLoading ->
				preview.resources.getString(R.string.connector_thread_preview_loading)
			item.previewType == ConnectorMessageType.PHOTO ->
				previewText(preview.resources, item, R.string.connector_message_photo)
			item.previewType == ConnectorMessageType.FILE ->
				previewText(preview.resources, item, R.string.connector_message_file)
			item.previewType == ConnectorMessageType.DELETED ->
				previewText(preview.resources, item, R.string.connector_message_deleted)
			item.hasPreviewText() -> previewText(preview.resources, item)
			else -> preview.resources.getString(R.string.connector_thread_preview_empty)
		}
		itemView.setOnClickListener { view -> listener.onItemClick(view, item) }
	}

	private companion object {
		fun titleText(resources: Resources, item: ConnectorInboxThreadItem): CharSequence {
			val title = item.title
			return if (isWhitespaceOrInvisible(title)) {
				resources.getString(R.string.connector_thread_title_fallback)
			} else {
				title
			}
		}

		/**
		 * `CharSequence.trim()` only strips JDK-whitespace code points, so a title
		 * made up solely of zero-width/format characters (e.g. U+200B, U+FEFF,
		 * U+200E/U+200F, U+2060) would otherwise pass the blank-title guard and
		 * render as an empty row with no accessible label.
		 *
		 * This walks code points via [String.codePointAt], not
		 * `CharSequence.codePoints()`: that default method was only added to the
		 * platform `CharSequence` interface at API 24, and briar-android's
		 * minSdkVersion is 21 with no core library desugaring configured, so
		 * calling it here would crash every connector inbox row bind on
		 * API 21-23. `codePointAt`/`Character.charCount` are concrete `String`/
		 * `Character` methods available on all supported API levels, and walking
		 * by code point (rather than by UTF-16 char) avoids misclassifying either
		 * half of a surrogate pair that encodes a visible supplementary-plane
		 * character (e.g. most emoji) as [Character.SURROGATE] on its own.
		 */
		fun isWhitespaceOrInvisible(title: String): Boolean {
			var index = 0
			while (index < title.length) {
				val codePoint = title.codePointAt(index)
				if (!isWhitespaceOrInvisible(codePoint)) return false
				index += Character.charCount(codePoint)
			}
			return true
		}

		fun isWhitespaceOrInvisible(codePoint: Int): Boolean {
			if (Character.isWhitespace(codePoint)) return true
			val type = Character.getType(codePoint).toByte()
			return type == Character.FORMAT || type == Character.CONTROL || type == Character.SURROGATE
		}

		fun sourceLabel(item: ConnectorInboxThreadItem): CharSequence = item.connectorSource.displayName

		@DrawableRes
		fun sourceIconRes(item: ConnectorInboxThreadItem): Int =
			if (item.connectorSource.id == ConnectorSources.TELEGRAM_ID) {
				R.drawable.ic_telegram
			} else {
				R.drawable.ic_link_menu
			}

		fun sourceContentDescription(resources: Resources, item: ConnectorInboxThreadItem): CharSequence =
			resources.getString(
				R.string.connector_thread_source_content_description,
				item.connectorSource.displayName,
			)

		fun dateVisibility(item: ConnectorInboxThreadItem): Int =
			if (item.latestActivityMillis > 0) View.VISIBLE else View.GONE

		fun dateText(context: Context, item: ConnectorInboxThreadItem): CharSequence {
			val latestActivityMillis = item.latestActivityMillis
			if (latestActivityMillis <= 0) return ""
			return formatDate(context, latestActivityMillis)
		}

		fun previewText(resources: Resources, item: ConnectorInboxThreadItem): CharSequence =
			previewText(resources, item, item.previewText)

		fun previewText(
			resources: Resources,
			item: ConnectorInboxThreadItem,
			@androidx.annotation.StringRes textRes: Int,
		): CharSequence = previewText(resources, item, resources.getString(textRes))

		private fun previewText(
			resources: Resources,
			item: ConnectorInboxThreadItem,
			text: CharSequence,
		): CharSequence = if (!item.isLastMessageOutgoing) {
			text
		} else {
			resources.getString(
				R.string.connector_thread_preview_outgoing,
				text,
			)
		}
	}
}
