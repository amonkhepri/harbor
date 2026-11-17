package org.briarproject.briar.android.connector

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.briarproject.briar.R
import org.briarproject.briar.android.util.UiUtils.formatDate
import org.briarproject.briar.android.util.UiUtils.formatDateFull
import org.briarproject.briar.api.connector.ConnectorMessageReceiptState
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReactionSummary
import org.briarproject.briar.api.connector.ConnectorSources

internal class ConnectorConversationAdapter(
	private val onDeleteClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	private val onRetrySendClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	private val onFileClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	var thumbnailManager: ConnectorThumbnailManager? = null,
	var onReplyClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	/** True when the own-message action opens the edit dialog for text rows (MX-058). */
	var editsOwnText: Boolean = false,
	var onReactClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	var onLinkClicked: ((String) -> Unit)? = null,
) : ListAdapter<ConnectorConversationRow, RecyclerView.ViewHolder>(RowCallback()) {

	override fun getItemViewType(position: Int): Int = when (getItem(position)) {
		is ConnectorConversationRow.Message -> VIEW_TYPE_MESSAGE
		is ConnectorConversationRow.DaySeparator -> VIEW_TYPE_DAY_SEPARATOR
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		if (viewType == VIEW_TYPE_DAY_SEPARATOR) {
			return DaySeparatorViewHolder(
				inflater.inflate(R.layout.list_item_connector_day_separator, parent, false),
			)
		}
		val view = inflater.inflate(R.layout.list_item_connector_message, parent, false)
		return MessageViewHolder(
			view,
			onDeleteClicked,
			onRetrySendClicked,
			onFileClicked,
			thumbnailManager,
			onReplyClicked,
			editsOwnText,
			onReactClicked,
		).apply {
			onLinkClicked = this@ConnectorConversationAdapter.onLinkClicked
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		when (val row = getItem(position)) {
			is ConnectorConversationRow.Message -> (holder as MessageViewHolder).let {
				it.onLinkClicked = onLinkClicked
				it.bind(row.item)
			}
			is ConnectorConversationRow.DaySeparator ->
				(holder as DaySeparatorViewHolder).bind(row.dayStartMillis)
		}
	}

	override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
		(holder as? MessageViewHolder)?.recycle()
		super.onViewRecycled(holder)
	}

	class DaySeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		private val label: TextView = itemView.findViewById(R.id.connectorDaySeparatorText)

		init {
			ViewCompat.setAccessibilityHeading(label, true)
		}

		fun bind(dayStartMillis: Long) {
			label.text = formatDateFull(label.context, dayStartMillis)
		}
	}

	class MessageViewHolder(
		itemView: View,
		private val onDeleteClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
		private val onRetrySendClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
		private val onFileClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
		private val thumbnailManager: ConnectorThumbnailManager? = null,
		private val onReplyClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
		private val editsOwnText: Boolean = false,
		private val onReactClicked: ((ConnectorConversationMessageItem) -> Unit)? = null,
	) : RecyclerView.ViewHolder(itemView) {
		constructor(itemView: View, onDeleteClicked: ((ConnectorConversationMessageItem) -> Unit)?) :
			this(itemView, onDeleteClicked, null, null, null, null, false, null)
		var onLinkClicked: ((String) -> Unit)? = null
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

		private val reactionChips: ConnectorReactionFlowLayout =
			itemView.findViewById(R.id.connectorReactionChips)

		private var boundThreadId: String? = null
		private var boundMessageId: String? = null
		private var thumbnailCancellation: Cancellable? = null

		fun recycle() {
			reactionChips.removeAllViews()
			reactionChips.visibility = View.GONE
			thumbnailCancellation?.cancel()
			thumbnailCancellation = null
			boundThreadId = null
			boundMessageId = null
			onLinkClicked = null
			thumbnail.setImageDrawable(null)
			thumbnail.visibility = View.GONE
			text.movementMethod = null
			text.isFocusable = false
			text.isClickable = false
			text.isLongClickable = false
			text.setOnLongClickListener(null)
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
			val showChips = item.connectorSource == ConnectorSources.MATRIX &&
				item.reactions.any { it.senderIds.isNotEmpty() }
			reactionChips.removeAllViews()
			reactionChips.visibility = if (showChips) View.VISIBLE else View.GONE
			if (showChips) {
				item.reactions.forEach { reaction ->
					val chip = TextView(itemView.context)
					chip.text = text.resources.getString(
						R.string.connector_message_reaction_summary,
						reaction.key,
						reaction.count,
					)
					val touchTarget = (48 * text.resources.displayMetrics.density).toInt()
					chip.minHeight = touchTarget
					chip.minWidth = touchTarget
					chip.gravity = Gravity.CENTER
					val padding = touchTarget / 6
					chip.setPadding(padding, 0, padding, 0)
					if (reaction.senderIds.isNotEmpty()) {
						chip.setOnLongClickListener {
							showReactionSenders(reaction)
							true
						}
					}
					reactionChips.addView(chip)
				}
			}
			val allReactions = if (item.reactions.isEmpty()) {
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
			val reactionSummary = if (showChips) null else allReactions
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
						allReactions,
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
			if (item.type == ConnectorMessageType.TEXT) {
				ConnectorMessageLinks.linkify(text, onLinkClicked)
			} else {
				text.movementMethod = null
				text.isFocusable = false
				text.isClickable = false
				text.isLongClickable = false
				text.setOnLongClickListener(null)
			}
			direction.setText(
				when (item.localEchoStatus) {
					ConnectorLocalEchoStatus.PENDING -> R.string.connector_message_sending
					ConnectorLocalEchoStatus.SENT -> R.string.connector_message_sent
					ConnectorLocalEchoStatus.FAILED -> R.string.connector_conversation_send_failed
					null -> if (item.isOutgoing) {
						when (item.receiptState) {
							ConnectorMessageReceiptState.READ -> R.string.connector_message_read
							ConnectorMessageReceiptState.UNREAD -> R.string.connector_message_unread
							ConnectorMessageReceiptState.UNKNOWN ->
								R.string.connector_conversation_direction_outgoing
						}
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
				val canDelete = item.isOutgoing &&
					item.localEchoStatus == null &&
					item.type != ConnectorMessageType.DELETED &&
					onDeleteClicked != null
				val canReply = onReplyClicked != null && ConnectorReplyTarget.canReplyTo(item)
				val canReact = onReactClicked != null &&
					item.localEchoStatus == null &&
					item.type != ConnectorMessageType.DELETED &&
					item.connectorMessageId.isNotBlank()
				val actionCount =
					(if (canDelete) 1 else 0) + (if (canReply) 1 else 0) + (if (canReact) 1 else 0)
				when {
					actionCount > 1 -> showMessageActions(item, canDelete, canReply, canReact)
					canDelete -> onDeleteClicked?.invoke(item)
					canReply -> onReplyClicked?.invoke(item)
					canReact -> onReactClicked?.invoke(item)
				}
				actionCount > 0
			}
		}

		private fun showReactionSenders(reaction: ConnectorReactionSummary) {
			val senders = reaction.senderIds.take(ConnectorReactionSummary.MAX_SENDERS)
			val entries = senders.toMutableList()
			if (reaction.count > senders.size) {
				entries.add(
					text.resources.getString(
						R.string.connector_reaction_more_senders,
						reaction.count - senders.size,
					),
				)
			}
			AlertDialog.Builder(itemView.context, R.style.BriarDialogTheme)
				.setTitle(reaction.key)
				.setItems(entries.toTypedArray(), null)
				.setPositiveButton(android.R.string.ok, null)
				.show()
		}

		private fun showMessageActions(
			item: ConnectorConversationMessageItem,
			canDelete: Boolean,
			canReply: Boolean,
			canReact: Boolean,
		) {
			val popup = PopupMenu(itemView.context, itemView)
			if (canReact) {
				popup.menu.add(Menu.NONE, ACTION_REACT, 0, R.string.connector_conversation_react)
			}
			if (canReply) {
				popup.menu.add(Menu.NONE, ACTION_REPLY, 1, R.string.connector_conversation_reply)
			}
			if (canDelete) {
				val ownAction = if (editsOwnText && item.type == ConnectorMessageType.TEXT) {
					R.string.connector_conversation_edit
				} else {
					R.string.delete
				}
				popup.menu.add(Menu.NONE, ACTION_OWN, 2, ownAction)
			}
			popup.setOnMenuItemClickListener { menuItem ->
				when (menuItem.itemId) {
					ACTION_REACT -> onReactClicked?.invoke(item)
					ACTION_REPLY -> onReplyClicked?.invoke(item)
					ACTION_OWN -> onDeleteClicked?.invoke(item)
				}
				true
			}
			popup.show()
		}

		private companion object {
			const val ACTION_REPLY = 1
			const val ACTION_OWN = 2
			const val ACTION_REACT = 3
		}
	}

	private class RowCallback : ItemCallback<ConnectorConversationRow>() {

		override fun areItemsTheSame(
			r1: ConnectorConversationRow,
			r2: ConnectorConversationRow,
		): Boolean = r1.rowId == r2.rowId

		override fun areContentsTheSame(
			r1: ConnectorConversationRow,
			r2: ConnectorConversationRow,
		): Boolean {
			if (r1 !is ConnectorConversationRow.Message ||
				r2 !is ConnectorConversationRow.Message
			) {
				return r1 == r2
			}
			val i1 = r1.item
			val i2 = r2.item
			return i1.dateMillis == i2.dateMillis &&
				i1.isOutgoing == i2.isOutgoing &&
				i1.text == i2.text &&
				i1.type == i2.type &&
				i1.isEdited == i2.isEdited &&
				i1.isReply == i2.isReply &&
				i1.reactions == i2.reactions &&
				i1.localEchoStatus == i2.localEchoStatus &&
				i1.attachment == i2.attachment &&
				i1.downloadState == i2.downloadState &&
				i1.downloadedFile == i2.downloadedFile &&
				i1.receiptState == i2.receiptState
		}
	}

	private companion object {
		const val VIEW_TYPE_MESSAGE = 0
		const val VIEW_TYPE_DAY_SEPARATOR = 1
	}
}

/** Lays reaction chips out in horizontal rows that wrap at the available width. */
internal class ConnectorReactionFlowLayout(
	context: Context,
	attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		for (i in 0 until childCount) {
			measureChild(getChildAt(i), widthMeasureSpec, heightMeasureSpec)
		}
		val maxWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
			Int.MAX_VALUE
		} else {
			MeasureSpec.getSize(widthMeasureSpec)
		}
		var widest = 0
		val height = flow(maxWidth) { child, left, _ ->
			widest = maxOf(widest, left + child.measuredWidth)
		}
		setMeasuredDimension(
			resolveSize(widest, widthMeasureSpec),
			resolveSize(height, heightMeasureSpec),
		)
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
		val width = r - l
		val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
		flow(width) { child, left, top ->
			val start = if (rtl) width - left - child.measuredWidth else left
			child.layout(start, top, start + child.measuredWidth, top + child.measuredHeight)
		}
	}

	/** Places each measured child and returns the total height of all rows. */
	private fun flow(maxWidth: Int, place: (View, Int, Int) -> Unit): Int {
		var left = 0
		var top = 0
		var rowHeight = 0
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (left > 0 && left + child.measuredWidth > maxWidth) {
				left = 0
				top += rowHeight
				rowHeight = 0
			}
			place(child, left, top)
			left += child.measuredWidth
			rowHeight = maxOf(rowHeight, child.measuredHeight)
		}
		return top + rowHeight
	}
}
