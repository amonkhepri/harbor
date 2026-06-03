package org.briarproject.briar.android.telegram

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.briarproject.briar.R
import org.briarproject.briar.android.util.UiUtils.formatDate

class TelegramConversationAdapter :
	ListAdapter<TelegramConversationUiMessage,
		TelegramConversationAdapter.MessageViewHolder>(MessageCallback()) {

	override fun onCreateViewHolder(parent: ViewGroup,
		viewType: Int): MessageViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(
			R.layout.list_item_telegram_message, parent, false)
		return MessageViewHolder(view)
	}

	override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		private val text: TextView = itemView.findViewById(R.id.telegramMessageText)
		private val direction: TextView =
			itemView.findViewById(R.id.telegramMessageDirection)
		private val date: TextView = itemView.findViewById(R.id.telegramMessageDate)

		fun bind(item: TelegramConversationUiMessage) {
			text.text = item.text
			direction.setText(directionText(item))
			date.visibility = dateVisibility(item)
			date.text = dateText(date.context, item)
		}
	}

	private class MessageCallback : ItemCallback<TelegramConversationUiMessage>() {

		override fun areItemsTheSame(
			i1: TelegramConversationUiMessage,
			i2: TelegramConversationUiMessage,
		): Boolean = i1.stableId == i2.stableId

		override fun areContentsTheSame(
			i1: TelegramConversationUiMessage,
			i2: TelegramConversationUiMessage,
		): Boolean =
			i1.dateMillis == i2.dateMillis &&
				i1.isOutgoing == i2.isOutgoing &&
				i1.text == i2.text
	}

	companion object {
		@JvmStatic
		fun dateVisibility(item: TelegramConversationUiMessage): Int =
			if (item.dateMillis > 0) View.VISIBLE else View.GONE

		@JvmStatic
		fun dateText(context: Context,
			item: TelegramConversationUiMessage): CharSequence {
			val dateMillis = item.dateMillis
			if (dateMillis <= 0) return ""
			return formatDate(context, dateMillis)
		}

		@JvmStatic
		fun directionText(item: TelegramConversationUiMessage): Int =
			if (item.isOutgoing)
				R.string.telegram_conversation_direction_outgoing
			else R.string.telegram_conversation_direction_incoming
	}
}
