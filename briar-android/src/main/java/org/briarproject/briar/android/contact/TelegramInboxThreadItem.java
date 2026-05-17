package org.briarproject.briar.android.contact;

import org.briarproject.briar.api.telegram.TelegramChat;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class TelegramInboxThreadItem implements InboxThreadItem {

	private final long chatId;
	private final String title;
	private final long latestActivityMillis;

	TelegramInboxThreadItem(TelegramChat chat) {
		this(chat.getId(), chat.getTitle(),
				chat.getLastMessageDateSeconds() * 1000L);
	}

	TelegramInboxThreadItem(long chatId, String title,
			long latestActivityMillis) {
		this.chatId = chatId;
		this.title = title;
		this.latestActivityMillis = latestActivityMillis;
	}

	public long getChatId() {
		return chatId;
	}

	public String getTitle() {
		return title;
	}

	@Override
	public String getStableId() {
		return "telegram:" + chatId;
	}

	@Override
	public long getLatestActivityMillis() {
		return latestActivityMillis;
	}

	@Override
	public Source getSource() {
		return Source.TELEGRAM;
	}
}
