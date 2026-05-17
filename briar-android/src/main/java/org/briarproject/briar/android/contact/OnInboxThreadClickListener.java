package org.briarproject.briar.android.contact;

import android.view.View;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface OnInboxThreadClickListener {

	void onBriarItemClick(View view, ContactListItem item);

	void onTelegramItemClick(View view, TelegramInboxThreadItem item);
}
