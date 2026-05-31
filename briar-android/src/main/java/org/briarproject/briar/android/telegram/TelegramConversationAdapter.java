package org.briarproject.briar.android.telegram;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.recyclerview.widget.DiffUtil.ItemCallback;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import static org.briarproject.briar.android.util.UiUtils.formatDate;

@NotNullByDefault
final class TelegramConversationAdapter extends
		ListAdapter<TelegramConversationUiMessage,
				TelegramConversationAdapter.MessageViewHolder> {

	TelegramConversationAdapter() {
		super(new MessageCallback());
	}

	@Override
	public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(
				R.layout.list_item_telegram_message, parent, false);
		return new MessageViewHolder(view);
	}

	@Override
	public void onBindViewHolder(MessageViewHolder holder, int position) {
		holder.bind(getItem(position));
	}

	static final class MessageViewHolder extends RecyclerView.ViewHolder {

		private final TextView text;
		private final TextView direction;
		private final TextView date;

		MessageViewHolder(View itemView) {
			super(itemView);
			text = itemView.findViewById(R.id.telegramMessageText);
			direction = itemView.findViewById(R.id.telegramMessageDirection);
			date = itemView.findViewById(R.id.telegramMessageDate);
		}

		void bind(TelegramConversationUiMessage item) {
			text.setText(item.getText());
			direction.setText(directionText(item));
			date.setVisibility(dateVisibility(item));
			date.setText(dateText(date.getContext(), item));
		}
	}

	static int dateVisibility(TelegramConversationUiMessage item) {
		return item.getDateMillis() > 0 ? View.VISIBLE : View.GONE;
	}

	static CharSequence dateText(Context context, TelegramConversationUiMessage item) {
		long dateMillis = item.getDateMillis();
		if (dateMillis <= 0) return "";
		return formatDate(context, dateMillis);
	}

	static int directionText(TelegramConversationUiMessage item) {
		return item.isOutgoing() ?
				R.string.telegram_conversation_direction_outgoing :
				R.string.telegram_conversation_direction_incoming;
	}

	private static final class MessageCallback extends
			ItemCallback<TelegramConversationUiMessage> {

		@Override
		public boolean areItemsTheSame(TelegramConversationUiMessage i1,
				TelegramConversationUiMessage i2) {
			return i1.getMessageId() == i2.getMessageId();
		}

		@Override
		public boolean areContentsTheSame(TelegramConversationUiMessage i1,
				TelegramConversationUiMessage i2) {
			return i1.getDateMillis() == i2.getDateMillis() &&
					i1.isOutgoing() == i2.isOutgoing() &&
					i1.getText().equals(i2.getText());
		}
	}
}
