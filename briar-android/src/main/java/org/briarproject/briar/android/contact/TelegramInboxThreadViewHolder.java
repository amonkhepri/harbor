package org.briarproject.briar.android.contact;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;

import static org.briarproject.briar.android.util.UiUtils.formatDate;

@UiThread
@NotNullByDefault
final class TelegramInboxThreadViewHolder extends
		androidx.recyclerview.widget.RecyclerView.ViewHolder {

	private final TextView title;
	private final TextView date;
	private final TextView preview;

	TelegramInboxThreadViewHolder(View view) {
		super(view);
		title = view.findViewById(R.id.telegramThreadTitle);
		date = view.findViewById(R.id.telegramThreadDate);
		preview = view.findViewById(R.id.telegramThreadPreview);
	}

	void bind(TelegramInboxThreadItem item,
			OnContactClickListener<TelegramInboxThreadItem> listener) {
		title.setText(titleText(title.getResources(), item));
		date.setVisibility(dateVisibility(item));
		date.setText(dateText(date.getContext(), item));
		if (item.isPreviewLoading()) {
			preview.setText(R.string.telegram_thread_preview_loading);
		} else if (item.hasPreviewText()) {
			preview.setText(previewText(preview.getResources(), item));
		} else {
			preview.setText(R.string.telegram_thread_preview_empty);
		}
		itemView.setOnClickListener(view -> listener.onItemClick(view, item));
	}

	static CharSequence titleText(Resources resources,
			TelegramInboxThreadItem item) {
		String title = item.getTitle();
		if (title.trim().isEmpty()) {
			return resources.getString(R.string.telegram_thread_title_fallback);
		}
		return title;
	}

	static int dateVisibility(TelegramInboxThreadItem item) {
		return item.getLatestActivityMillis() > 0 ? View.VISIBLE : View.GONE;
	}

	static CharSequence dateText(Context context, TelegramInboxThreadItem item) {
		long latestActivityMillis = item.getLatestActivityMillis();
		if (latestActivityMillis <= 0) return "";
		return formatDate(context, latestActivityMillis);
	}

	static CharSequence previewText(Resources resources,
			TelegramInboxThreadItem item) {
		if (!item.isLastMessageOutgoing()) return item.getPreviewText();
		return resources.getString(R.string.telegram_thread_preview_outgoing,
				item.getPreviewText());
	}
}
