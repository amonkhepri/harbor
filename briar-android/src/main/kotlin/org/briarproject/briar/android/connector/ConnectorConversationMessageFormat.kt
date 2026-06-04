package org.briarproject.briar.android.connector

import android.content.Context
import android.view.View
import org.briarproject.briar.R
import org.briarproject.briar.android.util.UiUtils.formatDate

internal fun dateVisibility(item: ConnectorConversationMessageItem): Int =
	if (item.dateMillis > 0) View.VISIBLE else View.GONE

internal fun dateText(
	context: Context,
	item: ConnectorConversationMessageItem,
): CharSequence {
	val dateMillis = item.dateMillis
	if (dateMillis <= 0) return ""
	return formatDate(context, dateMillis)
}

internal fun directionText(item: ConnectorConversationMessageItem): Int =
	if (item.isOutgoing) {
		R.string.connector_conversation_direction_outgoing
	} else {
		R.string.connector_conversation_direction_incoming
	}
