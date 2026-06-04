package org.briarproject.briar.android.telegram

import org.briarproject.briar.android.connector.ConnectorConversationMessageItem
import org.briarproject.briar.api.connector.ConnectorMessage

object TelegramConversationMapper {

	private val byDateAscending = compareBy<ConnectorMessage> {
		it.dateSeconds
	}.thenBy {
		it.sourceMessageOrder
	}.thenBy {
		it.messageId
	}

	@JvmStatic
	fun toUiMessages(
		messages: List<ConnectorMessage>,
	): List<ConnectorConversationMessageItem> {
		val items = ArrayList<ConnectorConversationMessageItem>(messages.size)
		for (message in messages.sortedWith(byDateAscending)) {
			val text = message.text
			if (text.trim().isEmpty()) continue
			items.add(ConnectorConversationMessageItem.from(message))
		}
		return items
	}
	}
