package org.briarproject.briar.android.contact;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
final class BriarInboxThreadItem implements InboxThreadItem {

	private final ContactListItem item;

	BriarInboxThreadItem(ContactListItem item) {
		this.item = item;
	}

	ContactListItem getItem() {
		return item;
	}

	@Override
	public String getStableId() {
		return "briar:" + item.getContact().getId().getInt();
	}

	@Override
	public long getLatestActivityMillis() {
		return item.getTimestamp();
	}

	@Override
	public Source getSource() {
		return Source.BRIAR;
	}
}
