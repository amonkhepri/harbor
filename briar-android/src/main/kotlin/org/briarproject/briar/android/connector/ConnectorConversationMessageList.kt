package org.briarproject.briar.android.connector

internal data class ConnectorConversationMessageListState(
	val messages: List<ConnectorConversationMessageItem> = emptyList(),
	val emptyText: String? = null,
	val isLoading: Boolean = false,
)

internal enum class ConnectorConversationAvailabilityState {
	LOADING,
	DISABLED,
	ACCOUNT_UNAVAILABLE,
	EMPTY,
	LOAD_FAILED,
}
