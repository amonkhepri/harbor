package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.telegram.TelegramChat

class TelegramInboxThreadItem(
	val chatId: Long,
	override val title: String,
	override val latestActivityMillis: Long,
	override val previewText: String,
	override val isLastMessageOutgoing: Boolean,
	override val isPreviewLoading: Boolean,
) : ConnectorInboxThreadItem {

	constructor(chat: TelegramChat) : this(
		chat.id,
		chat.title,
		chat.lastMessageDateSeconds * 1000L,
		cleanPreviewText(chat.lastMessageText),
		chat.lastMessageIsOutgoing,
		false
	)

	constructor(
		chatId: Long,
		title: String,
		latestActivityMillis: Long,
	) : this(chatId, title, latestActivityMillis, "", true)

	constructor(
		chatId: Long,
		title: String,
		latestActivityMillis: Long,
		previewText: String,
		previewLoading: Boolean,
	) : this(
		chatId,
		title,
		latestActivityMillis,
		previewText,
		false,
		previewLoading
	)

	override val connectorThreadId: String
		get() = chatId.toString()

	override val connectorSource = ConnectorSources.TELEGRAM
}

private fun cleanPreviewText(text: String): String =
	text.replace(Regex("\\s+"), " ").trim()
