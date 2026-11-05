package org.briarproject.briar.android.connector

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.briarproject.briar.R
import org.briarproject.briar.android.util.UiUtils.formatDate
import org.briarproject.briar.api.connector.ConnectorMessageType

internal class ConnectorConversationAdapter(
	private val onDeleteClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	private val onRetrySendClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	private val onFileClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	var thumbnailManager: ConnectorThumbnailManager? = null,
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
		return MessageViewHolder(
			view,
			onDeleteClicked,
			onRetrySendClicked,
			onFileClicked,
			thumbnailManager,
		)
	}

	override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	override fun onViewRecycled(holder: MessageViewHolder) {
		holder.recycle()
		super.onViewRecycled(holder)
	}

	class MessageViewHolder(
		itemView: View,
		private val onDeleteClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
		private val onRetrySendClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
		private val onFileClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
		private val thumbnailManager: ConnectorThumbnailManager? = null,
	) : RecyclerView.ViewHolder(itemView) {
		constructor(itemView: View, onDeleteClicked: ((ConnectorConversationMessageItem) -> Unit)?) :
			this(itemView, onDeleteClicked, null, null, null)
		private val text: TextView =
			itemView.findViewById(R.id.connectorMessageText)
		private val thumbnail: ImageView =
			itemView.findViewById(R.id.connectorMessageThumbnail)
		private val direction: TextView =
			itemView.findViewById(R.id.connectorMessageDirection)
		private val date: TextView =
			itemView.findViewById(R.id.connectorMessageDate)
		private val fileLayout: View =
			itemView.findViewById(R.id.connectorFileLayout)
		private val fileName: TextView =
			itemView.findViewById(R.id.connectorFileName)
		private val fileDetails: TextView =
			itemView.findViewById(R.id.connectorFileDetails)

		private var boundThreadId: String? = null
		private var boundMessageId: String? = null
		private var thumbnailCancellation: Cancellable? = null

		fun recycle() {
			thumbnailCancellation?.cancel()
			thumbnailCancellation = null
			boundThreadId = null
			boundMessageId = null
			thumbnail.setImageDrawable(null)
			thumbnail.visibility = View.GONE
		}

		fun bind(item: ConnectorConversationMessageItem) {
			thumbnailCancellation?.cancel()
			thumbnailCancellation = null
			boundThreadId = item.connectorThreadId
			boundMessageId = item.connectorMessageId

			if (item.type == ConnectorMessageType.PHOTO) {
				thumbnail.visibility = View.VISIBLE
				val manager = thumbnailManager
				if (manager != null) {
					val cached = manager.getCached(item.connectorThreadId, item.connectorMessageId)
					if (cached != null) {
						thumbnail.setImageBitmap(cached)
					} else {
						thumbnail.setImageDrawable(null)
						thumbnailCancellation = manager.loadThumbnail(item) { bitmap ->
							if (boundThreadId == item.connectorThreadId &&
								boundMessageId == item.connectorMessageId
							) {
								if (bitmap != null) {
									thumbnail.setImageBitmap(bitmap)
								} else {
									thumbnail.setImageResource(R.drawable.ic_image_broken)
								}
							}
						}
					}
				} else {
					thumbnail.setImageDrawable(null)
				}
			} else {
				thumbnail.visibility = View.GONE
				thumbnail.setImageDrawable(null)
			}
			val isFile = item.type == ConnectorMessageType.FILE
			val fileFallbackName = text.resources.getString(R.string.connector_message_file)
			val safeFileName = if (isFile) {
				ConnectorFileMetadataFormatter.sanitizeFileName(
					item.attachment?.fileName,
					fileFallbackName,
				)
			} else {
				""
			}
			val messageText = when (item.type) {
				ConnectorMessageType.PHOTO -> text.resources.getString(R.string.connector_message_photo)
				ConnectorMessageType.FILE -> safeFileName
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
			val reactionSummary = if (item.reactions.isEmpty()) {
				null
			} else {
				item.reactions.joinToString("  ") { reaction ->
					text.resources.getString(
						R.string.connector_message_reaction_summary,
						reaction.key,
						reaction.count,
					)
				}
			}
			val downloadStatus = when (item.downloadState) {
				ConnectorDownloadState.DOWNLOADING ->
					text.resources.getString(R.string.connector_file_downloading)
				ConnectorDownloadState.DOWNLOADED ->
					text.resources.getString(R.string.connector_file_downloaded)
				ConnectorDownloadState.FAILED ->
					text.resources.getString(R.string.connector_file_download_failed)
				null, ConnectorDownloadState.IDLE -> null
			}
			if (isFile) {
				val mimeType =
					ConnectorFileMetadataFormatter.sanitizeMimeType(item.attachment?.mimeType)
				val formattedSize = ConnectorFileMetadataFormatter.formatFileSize(
					itemView.context,
					item.attachment?.size,
				)
				fileName.text = decoratedText
				val baseDetails = ConnectorFileMetadataFormatter.formatFileDetails(
					text.resources,
					mimeType,
					formattedSize,
				)
				val details = if (downloadStatus != null) {
					if (baseDetails.isNotEmpty()) "$baseDetails • $downloadStatus" else downloadStatus
				} else {
					baseDetails
				}
				fileDetails.text = details
				fileDetails.visibility = if (details.isNotEmpty()) View.VISIBLE else View.GONE
				fileLayout.contentDescription =
					ConnectorFileMetadataFormatter.formatFileAccessibility(
						text.resources,
						decoratedText,
						mimeType,
						formattedSize,
						reactionSummary,
						downloadStatus,
					)
				fileLayout.visibility = View.VISIBLE
				if (onFileClicked != null) {
					fileLayout.setOnClickListener { onFileClicked.invoke(item) }
					fileLayout.isClickable = true
				} else {
					fileLayout.setOnClickListener(null)
					fileLayout.isClickable = false
				}
				if (reactionSummary != null) {
					text.text = reactionSummary
					text.visibility = View.VISIBLE
				} else if (item.attachment == null) {
					text.text = decoratedText
					text.visibility = View.GONE
				} else {
					text.text = ""
					text.visibility = View.GONE
				}
			} else {
				fileLayout.visibility = View.GONE
				fileLayout.contentDescription = null
				fileLayout.setOnClickListener(null)
				fileLayout.isClickable = false
				fileName.text = ""
				fileDetails.text = ""
				fileDetails.visibility = View.GONE
				val photoText = if (item.type == ConnectorMessageType.PHOTO && downloadStatus != null) {
					"$decoratedText • $downloadStatus"
				} else {
					decoratedText
				}
				text.text = if (reactionSummary == null) {
					photoText
				} else {
					text.resources.getString(
						R.string.connector_message_with_reactions,
						photoText,
						reactionSummary,
					)
				}
				text.visibility = View.VISIBLE
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
			if (item.isOutgoing &&
				item.type == ConnectorMessageType.TEXT &&
				item.localEchoStatus == ConnectorLocalEchoStatus.FAILED &&
				onRetrySendClicked != null
			) {
				itemView.setOnClickListener { onRetrySendClicked.invoke(item) }
			} else if (item.type == ConnectorMessageType.PHOTO &&
				item.attachment != null &&
				onFileClicked != null
			) {
				itemView.setOnClickListener { onFileClicked.invoke(item) }
			} else {
				itemView.setOnClickListener(null)
				itemView.isClickable = false
			}
			itemView.setOnLongClickListener {
				// Delete affordance is connector-neutral: own sent messages without pending
				// local echo and not already deleted trigger the callback when delete is supported.
				if (item.isOutgoing &&
					item.localEchoStatus == null &&
					item.type != ConnectorMessageType.DELETED &&
					onDeleteClicked != null
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
			i1.localEchoStatus == i2.localEchoStatus &&
			i1.attachment == i2.attachment &&
			i1.downloadState == i2.downloadState &&
			i1.downloadedFile == i2.downloadedFile
	}
}
