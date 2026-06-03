package org.briarproject.briar.android.contact

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import org.briarproject.briar.R
import org.briarproject.nullsafety.NullSafety

class InboxThreadAdapter(
	private val listener: OnInboxThreadClickListener,
) : ListAdapter<InboxThreadItem, ViewHolder>(InboxThreadCallback()) {

	override fun getItemViewType(position: Int): Int =
		if (getItem(position).connectorSource == null)
			VIEW_TYPE_BRIAR
		else VIEW_TYPE_TELEGRAM

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return if (viewType == VIEW_TYPE_BRIAR) {
			val view = inflater.inflate(R.layout.list_item_contact, parent, false)
			InboxContactListItemViewHolder(view)
		} else {
			val view = inflater.inflate(
				R.layout.list_item_telegram_thread, parent, false)
			TelegramInboxThreadViewHolder(view)
		}
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val item = getItem(position)
		if (holder is InboxContactListItemViewHolder) {
			val briarItem = (item as BriarInboxThreadItem).item
			holder.bindInbox(briarItem) { view, clickedItem ->
				listener.onBriarItemClick(view, clickedItem)
			}
		} else {
			(holder as TelegramInboxThreadViewHolder).bind(
				item as TelegramInboxThreadItem
			) { view, clickedItem ->
				listener.onTelegramItemClick(view, clickedItem)
			}
		}
	}

	internal class InboxThreadCallback : ItemCallback<InboxThreadItem>() {

		override fun areItemsTheSame(
			i1: InboxThreadItem,
			i2: InboxThreadItem,
		): Boolean = i1.stableId == i2.stableId

		override fun areContentsTheSame(
			i1: InboxThreadItem,
			i2: InboxThreadItem,
		): Boolean {
			if (i1.connectorSource != i2.connectorSource) return false
			if (i1 is ConnectorInboxThreadItem) {
				val t2 = i2 as ConnectorInboxThreadItem
				return i1.latestActivityMillis == t2.latestActivityMillis &&
					i1.title == t2.title &&
					i1.isPreviewLoading == t2.isPreviewLoading &&
					i1.isLastMessageOutgoing == t2.isLastMessageOutgoing &&
					i1.previewText == t2.previewText
			}
			val c1 = (i1 as BriarInboxThreadItem).item
			val c2 = (i2 as BriarInboxThreadItem).item
			if (c1.isEmpty != c2.isEmpty) return false
			if (c1.unreadCount != c2.unreadCount) return false
			if (c1.timestamp != c2.timestamp) return false
			if (c1.isConnected != c2.isConnected) return false
			if (!NullSafety.equals(c1.contact.alias, c2.contact.alias)) {
				return false
			}
			return NullSafety.equals(
				c1.authorInfo.avatarHeader,
				c2.authorInfo.avatarHeader
			)
		}
	}

	private class InboxContactListItemViewHolder(view: View) :
		ContactListItemViewHolder(view) {

		fun bindInbox(
			item: ContactListItem,
			listener: OnContactClickListener<ContactListItem>,
		) {
			super.bind(item, listener)
		}
	}

	companion object {
		private const val VIEW_TYPE_BRIAR = 0
		private const val VIEW_TYPE_TELEGRAM = 1
	}
}
