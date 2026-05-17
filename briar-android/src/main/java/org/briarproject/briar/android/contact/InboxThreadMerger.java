package org.briarproject.briar.android.contact;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@NotNullByDefault
final class InboxThreadMerger {

	private static final Comparator<InboxThreadItem> BY_LATEST_ACTIVITY =
			Comparator.comparingLong(InboxThreadItem::getLatestActivityMillis)
					.reversed()
					.thenComparing(InboxThreadItem::getStableId);

	static List<InboxThreadItem> merge(List<ContactListItem> briarItems,
			List<TelegramInboxThreadItem> telegramItems) {
		List<InboxThreadItem> items =
				new ArrayList<>(briarItems.size() + telegramItems.size());
		for (ContactListItem item : briarItems) {
			items.add(new BriarInboxThreadItem(item));
		}
		items.addAll(telegramItems);
		items.sort(BY_LATEST_ACTIVITY);
		return items;
	}

	static void sort(List<InboxThreadItem> items) {
		items.sort(BY_LATEST_ACTIVITY);
	}
}
