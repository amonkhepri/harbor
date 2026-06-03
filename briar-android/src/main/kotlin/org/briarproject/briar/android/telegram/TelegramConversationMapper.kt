package org.briarproject.briar.android.telegram

import org.briarproject.briar.api.telegram.TelegramMessage

object TelegramConversationMapper {

	private val byDateAscending = compareBy<TelegramConversationUiMessage> {
		it.dateMillis
	}.thenBy {
		it.messageId
	}

	@JvmStatic
	fun toUiMessages(
		messages: List<TelegramMessage>,
	): List<TelegramConversationUiMessage> {
		val items = ArrayList<TelegramConversationUiMessage>(messages.size)
		for (message in messages) {
			val text = message.text
			if (text.trim().isEmpty()) continue
			items.add(
				TelegramConversationUiMessage(
					message.messageId,
					message.dateSeconds * 1000L,
					message.isOutgoing,
					text
				)
			)
		}
		items.sortWith(byDateAscending)
		return items
	}
}
