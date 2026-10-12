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
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources

internal class ConnectorConversationAdapter(
	private val onDeleteClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
) : ListAdapter<
	ConnectorConversationMessageItem,
	ConnectorConversationAdapter.MessageViewHolder,
	>(MessageCallback()) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
		val view = LayoutInflater.from(parent.context).inflate(
			R.layout.list_item_connector_message,
			parent,
			false,
		)
		return MessageViewHolder(view, onDeleteClicked)
	}

	override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	class MessageViewHolder(
		itemView: View,
		private val onDeleteClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	) : RecyclerView.ViewHolder(itemView) {
		private val text: TextView =
			itemView.findViewById(R.id.connectorMessageText)
		private val direction: TextView =
			itemView.findViewById(R.id.connectorMessageDirection)
		private val date: TextView =
			itemView.findViewById(R.id.connectorMessageDate)

		fun bind(item: ConnectorConversationMessageItem) {
			val messageText = when (item.type) {
				ConnectorMessageType.PHOTO -> text.resources.getString(R.string.connector_message_photo)
				ConnectorMessageType.FILE -> text.resources.getString(R.string.connector_message_file)
				ConnectorMessageType.DELETED ->
					text.resources.getString(R.string.connector_message_deleted)
				else -> item.text
			}
			val replyText = if (item.isReply) {
				text.resources.getString(R.string.connector_message_reply, messageText)
			} else {
				messageText
			}
			val decoratedText = if (item.isEdited) {
				text.resources.getString(R.string.connector_message_edited, replyText)
			} else {
				replyText
			}
			text.text = if (item.reactions.isEmpty()) {
				decoratedText
			} else {
				val reactionText = item.reactions.joinToString("  ") { reaction ->
					text.resources.getString(
						R.string.connector_message_reaction_summary,
						reaction.key,
						reaction.count,
					)
				}
				text.resources.getString(
					R.string.connector_message_with_reactions,
					decoratedText,
					reactionText,
				)
			}
			direction.setText(
				when (item.localEchoStatus) {
					ConnectorLocalEchoStatus.PENDING -> R.string.connector_message_sending
					ConnectorLocalEchoStatus.SENT -> R.string.connector_message_sent
					ConnectorLocalEchoStatus.FAILED -> R.string.connector_conversation_send_failed
					null -> if (item.isOutgoing) {
						R.string.connector_conversation_direction_outgoing
					} else {
						R.string.connector_conversation_direction_incoming
					}
				},
			)
			date.visibility = if (item.dateMillis > 0) View.VISIBLE else View.GONE
			date.text = if (item.dateMillis > 0) formatDate(date.context, item.dateMillis) else ""
			itemView.setOnLongClickListener {
				val isMatrixLocalId = item.connectorSource.id == ConnectorSources.MATRIX_ID &&
					!item.connectorMessageId.startsWith("\$")
				if (item.isOutgoing &&
					item.localEchoStatus == null &&
					item.type != ConnectorMessageType.DELETED &&
					onDeleteClicked != null &&
					!isMatrixLocalId
				) {
					onDeleteClicked.invoke(item)
					true
				} else {
					false
				}
			}
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
			i1.text == i2.text &&
			i1.type == i2.type &&
			i1.isEdited == i2.isEdited &&
			i1.isReply == i2.isReply &&
			i1.reactions == i2.reactions &&
			i1.localEchoStatus == i2.localEchoStatus
	}
}
