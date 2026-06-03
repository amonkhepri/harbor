package org.briarproject.briar.android.contact

import android.view.View

interface OnInboxThreadClickListener {

	fun onBriarItemClick(view: View, item: ContactListItem)

	fun onTelegramItemClick(view: View, item: TelegramInboxThreadItem)
}
