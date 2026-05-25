package org.briarproject.briar.android.contact;

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
		title.setText(item.getTitle());
		date.setText(formatDate(date.getContext(),
				item.getLatestActivityMillis()));
		if (item.isPreviewLoading()) {
			preview.setText(R.string.telegram_thread_preview_loading);
		} else if (item.hasPreviewText()) {
			preview.setText(item.getPreviewText());
		} else {
			preview.setText(R.string.telegram_thread_preview_empty);
		}
		itemView.setOnClickListener(view -> listener.onItemClick(view, item));
	}
}
