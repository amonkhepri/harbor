package org.briarproject.briar.android.telegram;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
final class TelegramConversationUiMessage {

	private final long messageId;
	private final long dateMillis;
	private final boolean outgoing;
	private final String text;

	TelegramConversationUiMessage(long messageId, long dateMillis,
			boolean outgoing, String text) {
		this.messageId = messageId;
		this.dateMillis = dateMillis;
		this.outgoing = outgoing;
		this.text = text;
	}

	long getMessageId() {
		return messageId;
	}

	long getDateMillis() {
		return dateMillis;
	}

	boolean isOutgoing() {
		return outgoing;
	}

	String getText() {
		return text;
	}
}
