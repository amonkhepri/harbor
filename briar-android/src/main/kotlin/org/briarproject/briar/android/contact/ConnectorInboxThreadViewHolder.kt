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

@UiThread
class ConnectorInboxThreadViewHolder(view: View) : RecyclerView.ViewHolder(view) {
	private val sourceIcon: ImageView = view.findViewById(R.id.connectorThreadIcon)
	private val title: TextView = view.findViewById(R.id.connectorThreadTitle)
	private val date: TextView = view.findViewById(R.id.connectorThreadDate)
	private val badge: TextView = view.findViewById(R.id.connectorThreadBadge)
	private val preview: TextView = view.findViewById(R.id.connectorThreadPreview)

	fun bind(item: ConnectorInboxThreadItem,
		listener: OnContactClickListener<ConnectorInboxThreadItem>) {
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
			item.hasPreviewText() -> previewText(preview.resources, item)
			else -> preview.resources.getString(R.string.connector_thread_preview_empty)
		}
		itemView.setOnClickListener { view -> listener.onItemClick(view, item) }
	}

	companion object {
		@JvmStatic
		fun titleText(resources: Resources,
			item: ConnectorInboxThreadItem): CharSequence {
			val title = item.title
			return if (title.trim().isEmpty())
				resources.getString(R.string.connector_thread_title_fallback)
			else title
		}

		@JvmStatic
		fun sourceLabel(item: ConnectorInboxThreadItem): CharSequence =
			item.connectorSource.displayName

		@JvmStatic
		@DrawableRes
		fun sourceIconRes(item: ConnectorInboxThreadItem): Int =
			if (item.connectorSource.id == ConnectorSources.TELEGRAM_ID)
				R.drawable.ic_telegram
			else R.drawable.ic_link_menu

		@JvmStatic
		fun sourceContentDescription(resources: Resources,
			item: ConnectorInboxThreadItem): CharSequence =
			resources.getString(
				R.string.connector_thread_source_content_description,
				item.connectorSource.displayName
			)

		@JvmStatic
		fun dateVisibility(item: ConnectorInboxThreadItem): Int =
			if (item.latestActivityMillis > 0) View.VISIBLE else View.GONE

		@JvmStatic
		fun dateText(context: Context, item: ConnectorInboxThreadItem): CharSequence {
			val latestActivityMillis = item.latestActivityMillis
			if (latestActivityMillis <= 0) return ""
			return formatDate(context, latestActivityMillis)
		}

		@JvmStatic
		fun previewText(resources: Resources,
			item: ConnectorInboxThreadItem): CharSequence =
			if (!item.isLastMessageOutgoing) item.previewText
			else resources.getString(R.string.connector_thread_preview_outgoing,
				item.previewText)
	}
}
