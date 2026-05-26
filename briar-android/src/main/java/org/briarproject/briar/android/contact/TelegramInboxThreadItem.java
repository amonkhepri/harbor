package org.briarproject.briar.android.contact;

import org.briarproject.briar.api.telegram.TelegramChat;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class TelegramInboxThreadItem implements InboxThreadItem {

	private final long chatId;
	private final String title;
	private final long latestActivityMillis;
	private final String previewText;
	private final boolean lastMessageOutgoing;
	private final boolean previewLoading;

	TelegramInboxThreadItem(TelegramChat chat) {
		this(chat.getId(), chat.getTitle(),
				chat.getLastMessageDateSeconds() * 1000L,
				cleanPreviewText(chat.getLastMessageText()),
				chat.getLastMessageIsOutgoing(), false);
	}

	TelegramInboxThreadItem(long chatId, String title,
			long latestActivityMillis) {
		this(chatId, title, latestActivityMillis, "", true);
	}

	TelegramInboxThreadItem(long chatId, String title,
			long latestActivityMillis, String previewText,
			boolean previewLoading) {
		this(chatId, title, latestActivityMillis, previewText, false,
				previewLoading);
	}

	TelegramInboxThreadItem(long chatId, String title,
			long latestActivityMillis, String previewText,
			boolean lastMessageOutgoing, boolean previewLoading) {
		this.chatId = chatId;
		this.title = title;
		this.latestActivityMillis = latestActivityMillis;
		this.previewText = previewText;
		this.lastMessageOutgoing = lastMessageOutgoing;
		this.previewLoading = previewLoading;
	}

	public long getChatId() {
		return chatId;
	}

	public String getTitle() {
		return title;
	}

	public String getPreviewText() {
		return previewText;
	}

	public boolean hasPreviewText() {
		return !previewText.isEmpty();
	}

	public boolean isLastMessageOutgoing() {
		return lastMessageOutgoing;
	}

	public boolean isPreviewLoading() {
		return previewLoading;
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

	private static String cleanPreviewText(String text) {
		return text.replaceAll("\\s+", " ").trim();
	}
}
