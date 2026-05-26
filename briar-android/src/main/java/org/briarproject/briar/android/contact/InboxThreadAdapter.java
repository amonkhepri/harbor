package org.briarproject.briar.android.contact;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.nullsafety.NullSafety;

import androidx.recyclerview.widget.DiffUtil.ItemCallback;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

@NotNullByDefault
final class InboxThreadAdapter extends
		ListAdapter<InboxThreadItem, ViewHolder> {

	private static final int VIEW_TYPE_BRIAR = 0;
	private static final int VIEW_TYPE_TELEGRAM = 1;

	private final OnInboxThreadClickListener listener;

	InboxThreadAdapter(OnInboxThreadClickListener listener) {
		super(new InboxThreadCallback());
		this.listener = listener;
	}

	@Override
	public int getItemViewType(int position) {
		return getItem(position).getSource() == InboxThreadItem.Source.BRIAR
				? VIEW_TYPE_BRIAR
				: VIEW_TYPE_TELEGRAM;
	}

	@Override
	public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == VIEW_TYPE_BRIAR) {
			View view = inflater.inflate(R.layout.list_item_contact, parent,
					false);
			return new ContactListItemViewHolder(view);
		}
		View view = inflater.inflate(R.layout.list_item_telegram_thread, parent,
				false);
		return new TelegramInboxThreadViewHolder(view);
	}

	@Override
	public void onBindViewHolder(ViewHolder holder, int position) {
		InboxThreadItem item = getItem(position);
		if (holder instanceof ContactListItemViewHolder) {
			ContactListItem briarItem =
					((BriarInboxThreadItem) item).getItem();
			((ContactListItemViewHolder) holder).bind(briarItem,
					listener::onBriarItemClick);
		} else {
			((TelegramInboxThreadViewHolder) holder).bind(
					(TelegramInboxThreadItem) item,
					listener::onTelegramItemClick);
		}
	}

	@NotNullByDefault
	private static class InboxThreadCallback
			extends ItemCallback<InboxThreadItem> {

		@Override
		public boolean areItemsTheSame(InboxThreadItem i1,
				InboxThreadItem i2) {
			return i1.getStableId().equals(i2.getStableId());
		}

		@Override
		public boolean areContentsTheSame(InboxThreadItem i1,
				InboxThreadItem i2) {
			if (i1.getSource() != i2.getSource()) return false;
			if (i1 instanceof TelegramInboxThreadItem) {
				TelegramInboxThreadItem t1 = (TelegramInboxThreadItem) i1;
				TelegramInboxThreadItem t2 = (TelegramInboxThreadItem) i2;
				return t1.getLatestActivityMillis() ==
						t2.getLatestActivityMillis() &&
						t1.getTitle().equals(t2.getTitle()) &&
						t1.isPreviewLoading() == t2.isPreviewLoading() &&
						t1.isLastMessageOutgoing() ==
								t2.isLastMessageOutgoing() &&
						t1.getPreviewText().equals(t2.getPreviewText());
			}
			ContactListItem c1 = ((BriarInboxThreadItem) i1).getItem();
			ContactListItem c2 = ((BriarInboxThreadItem) i2).getItem();
			if (c1.isEmpty() != c2.isEmpty()) return false;
			if (c1.getUnreadCount() != c2.getUnreadCount()) return false;
			if (c1.getTimestamp() != c2.getTimestamp()) return false;
			if (c1.isConnected() != c2.isConnected()) return false;
			if (!NullSafety.equals(c1.getContact().getAlias(),
					c2.getContact().getAlias())) {
				return false;
			}
			return NullSafety.equals(c1.getAuthorInfo().getAvatarHeader(),
					c2.getAuthorInfo().getAvatarHeader());
		}
	}
}
