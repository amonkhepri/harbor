package org.briarproject.briar.android.contact

import org.briarproject.briar.R
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.EMPTY
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.LOADING
import org.briarproject.briar.android.contact.ConnectorInboxAvailabilityState.NONE
import org.briarproject.briar.api.connector.ConnectorSources

sealed class InboxSourceFilter {

	abstract fun matches(item: InboxThreadItem): Boolean

	open fun emptyText(state: ConnectorInboxAvailabilityState): Int = when (state) {
		LOADING -> R.string.connector_inbox_loading
		ACCOUNT_UNAVAILABLE -> R.string.connector_inbox_account_unavailable
		LOAD_FAILED -> R.string.connector_inbox_load_failed
		EMPTY, NONE -> R.string.connector_inbox_empty
	}

	open fun emptyAction(state: ConnectorInboxAvailabilityState): Int = 0

	object All : InboxSourceFilter() {
		override fun matches(item: InboxThreadItem): Boolean = true
		override fun emptyText(state: ConnectorInboxAvailabilityState): Int =
			ContactListFragment.emptyTextForState(state)
		override fun emptyAction(state: ConnectorInboxAvailabilityState): Int =
			ContactListFragment.emptyActionTextForState(state)
	}

	object Matrix : InboxSourceFilter() {
		override fun matches(item: InboxThreadItem): Boolean =
			item.connectorSource?.id == ConnectorSources.MATRIX_ID
	}

	object Telegram : InboxSourceFilter() {
		override fun matches(item: InboxThreadItem): Boolean =
			item.connectorSource?.id == ConnectorSources.TELEGRAM_ID
	}

	object Briar : InboxSourceFilter() {
		override fun matches(item: InboxThreadItem): Boolean = item.connectorSource == null
		override fun emptyText(state: ConnectorInboxAvailabilityState): Int = R.string.no_contacts
		override fun emptyAction(state: ConnectorInboxAvailabilityState): Int =
			R.string.no_contacts_action
	}
}
