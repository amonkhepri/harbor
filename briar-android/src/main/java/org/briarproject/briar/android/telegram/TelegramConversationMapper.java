package org.briarproject.briar.android.telegram;

import org.briarproject.briar.api.telegram.TelegramMessage;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@NotNullByDefault
final class TelegramConversationMapper {

	private static final Comparator<TelegramConversationUiMessage>
			BY_DATE_ASCENDING =
			Comparator.comparingLong(TelegramConversationUiMessage::getDateMillis)
					.thenComparingLong(
							TelegramConversationUiMessage::getMessageId);

	static List<TelegramConversationUiMessage> toUiMessages(
			List<TelegramMessage> messages) {
		List<TelegramConversationUiMessage> items =
				new ArrayList<>(messages.size());
		for (TelegramMessage message : messages) {
			if (message.getText().isEmpty()) continue;
			items.add(new TelegramConversationUiMessage(
					message.getMessageId(),
					message.getDateSeconds() * 1000L,
					message.isOutgoing(),
					message.getText()
			));
		}
		items.sort(BY_DATE_ASCENDING);
		return items;
	}
}
