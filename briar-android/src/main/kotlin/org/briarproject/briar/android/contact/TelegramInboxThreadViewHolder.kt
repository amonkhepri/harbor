package org.briarproject.briar.android.contact

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.recyclerview.widget.RecyclerView
import org.briarproject.briar.R
import org.briarproject.briar.android.util.UiUtils.formatDate

@UiThread
class TelegramInboxThreadViewHolder(view: View) : RecyclerView.ViewHolder(view) {
	private val title: TextView = view.findViewById(R.id.telegramThreadTitle)
	private val date: TextView = view.findViewById(R.id.telegramThreadDate)
	private val preview: TextView = view.findViewById(R.id.telegramThreadPreview)

	fun bind(item: TelegramInboxThreadItem,
		listener: OnContactClickListener<TelegramInboxThreadItem>) {
		title.text = titleText(title.resources, item)
		date.visibility = dateVisibility(item)
		date.text = dateText(date.context, item)
		preview.text = when {
			item.isPreviewLoading ->
				preview.resources.getString(R.string.telegram_thread_preview_loading)
			item.hasPreviewText() -> previewText(preview.resources, item)
			else -> preview.resources.getString(R.string.telegram_thread_preview_empty)
		}
		itemView.setOnClickListener { view -> listener.onItemClick(view, item) }
	}

	companion object {
		@JvmStatic
		fun titleText(resources: Resources,
			item: TelegramInboxThreadItem): CharSequence {
			val title = item.title
			return if (title.trim().isEmpty())
				resources.getString(R.string.telegram_thread_title_fallback)
			else title
		}

		@JvmStatic
		fun dateVisibility(item: TelegramInboxThreadItem): Int =
			if (item.latestActivityMillis > 0) View.VISIBLE else View.GONE

		@JvmStatic
		fun dateText(context: Context, item: TelegramInboxThreadItem): CharSequence {
			val latestActivityMillis = item.latestActivityMillis
			if (latestActivityMillis <= 0) return ""
			return formatDate(context, latestActivityMillis)
		}

		@JvmStatic
		fun previewText(resources: Resources,
			item: TelegramInboxThreadItem): CharSequence =
			if (!item.isLastMessageOutgoing) item.previewText
			else resources.getString(R.string.telegram_thread_preview_outgoing,
				item.previewText)
	}
}
