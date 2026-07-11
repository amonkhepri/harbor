package org.briarproject.briar.android.connector

internal data class ConnectorConversationMessageListState(
	val messages: List<ConnectorConversationMessageItem> = emptyList(),
	val emptyText: String? = null,
	val visibleStatusText: String? = null,
	val isLoading: Boolean = false,
)

internal enum class ConnectorConversationAvailabilityState {
	LOADING,
	DISABLED,
	ACCOUNT_UNAVAILABLE,
	EMPTY,
	LOAD_FAILED,
}

internal fun ConnectorConversationMessageListState.withAvailabilityState(
	availabilityState: ConnectorConversationAvailabilityState,
	previousState: ConnectorConversationMessageListState,
	emptyText: String,
): ConnectorConversationMessageListState {
	val nextMessages = if (availabilityState.shouldKeepPreviousMessages()) {
		previousState.messages.ifEmpty { messages }
	} else {
		messages
	}
	return copy(
		messages = nextMessages,
		emptyText = emptyText,
		visibleStatusText = if (nextMessages.isNotEmpty() &&
			availabilityState.shouldKeepPreviousMessages()
		) {
			emptyText
		} else {
			null
		},
		isLoading = false,
	)
}

private fun ConnectorConversationAvailabilityState.shouldKeepPreviousMessages(): Boolean =
	this == ConnectorConversationAvailabilityState.ACCOUNT_UNAVAILABLE ||
		this == ConnectorConversationAvailabilityState.LOAD_FAILED
