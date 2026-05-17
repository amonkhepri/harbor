package org.briarproject.briar.android.contact;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface InboxThreadItem {

	enum Source {BRIAR, TELEGRAM}

	String getStableId();

	long getLatestActivityMillis();

	Source getSource();
}
