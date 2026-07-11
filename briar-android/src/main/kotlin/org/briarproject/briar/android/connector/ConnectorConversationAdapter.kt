package org.briarproject.briar.android.connector

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.briarproject.briar.R
import org.briarproject.briar.android.util.UiUtils.formatDate

internal class ConnectorConversationAdapter :
	ListAdapter<
		ConnectorConversationMessageItem,
		ConnectorConversationAdapter.MessageViewHolder,
		>(MessageCallback()) {

	fun submitState(state: ConnectorConversationMessageListState) {
		submitList(state.messages)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(
			R.layout.list_item_connector_message,
			parent,
			false,
		)
		return MessageViewHolder(view)
	}

	override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		private val text: TextView =
			itemView.findViewById(R.id.connectorMessageText)
		private val direction: TextView =
			itemView.findViewById(R.id.connectorMessageDirection)
		private val date: TextView =
			itemView.findViewById(R.id.connectorMessageDate)

		fun bind(item: ConnectorConversationMessageItem) {
			text.text = item.text
			direction.setText(
				if (item.isOutgoing) {
					R.string.connector_conversation_direction_outgoing
				} else {
					R.string.connector_conversation_direction_incoming
				},
			)
			date.visibility = if (item.dateMillis > 0) View.VISIBLE else View.GONE
			date.text = if (item.dateMillis > 0) formatDate(date.context, item.dateMillis) else ""
		}
	}

	private class MessageCallback : ItemCallback<ConnectorConversationMessageItem>() {

		override fun areItemsTheSame(
			i1: ConnectorConversationMessageItem,
			i2: ConnectorConversationMessageItem,
		): Boolean = i1.stableId == i2.stableId

		override fun areContentsTheSame(
			i1: ConnectorConversationMessageItem,
			i2: ConnectorConversationMessageItem,
		): Boolean = i1.dateMillis == i2.dateMillis &&
			i1.isOutgoing == i2.isOutgoing &&
			i1.text == i2.text
	}
}
