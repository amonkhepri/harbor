package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSource
import java.util.UUID

internal data class ConnectorConversationMessageListState(
	val messages: List<ConnectorConversationMessageItem> = emptyList(),
	val emptyText: String? = null,
	val visibleStatusText: String? = null,
	val isLoading: Boolean = false,
	val localEchoes: List<ConnectorLocalEcho> = emptyList(),
) {
	fun beginTextSend(
		source: ConnectorSource,
		threadId: String,
		text: String,
		now: Long = System.currentTimeMillis(),
	): ConnectorConversationMessageListState =
		beginSend(source, threadId, ConnectorMessageType.TEXT, text, now)

	/** Image/file sends have no caption to show up front, so the row carries the type alone. */
	fun beginMediaSend(
		source: ConnectorSource,
		threadId: String,
		type: ConnectorMessageType,
		now: Long = System.currentTimeMillis(),
	): ConnectorConversationMessageListState = beginSend(source, threadId, type, "", now)

	private fun beginSend(
		source: ConnectorSource,
		threadId: String,
		type: ConnectorMessageType,
		text: String,
		now: Long,
	): ConnectorConversationMessageListState {
		val item = ConnectorConversationMessageItem(
			source,
			threadId,
			UUID.randomUUID().toString(),
			now,
			true,
			text,
			type,
			localEchoStatus = ConnectorLocalEchoStatus.PENDING,
		)
		return copy(
			messages = messages + item,
			isLoading = false,
			localEchoes = localEchoes + ConnectorLocalEcho(
				item,
				messages.filter { it.localEchoStatus == null }.map { it.stableId }.toSet(),
			),
		)
	}

	fun completeSend(id: String, sent: Boolean): ConnectorConversationMessageListState {
		val echoes = localEchoes.map {
			if (it.item.stableId != id) {
				it
			} else {
				it.copy(
					completed = true,
					item = it.item.copy(
						localEchoStatus = if (sent) {
							ConnectorLocalEchoStatus.SENT
						} else {
							ConnectorLocalEchoStatus.FAILED
						},
					),
				)
			}
		}
		return copy(
			localEchoes = echoes,
			// Completion updates visible rows without restoring echoes hidden by availability.
			messages = messages.map { message ->
				echoes.firstOrNull { it.item.stableId == message.stableId }?.item ?: message
			},
		)
	}

	fun reconcile(
		serverMessages: List<ConnectorConversationMessageItem>,
	): ConnectorConversationMessageListState {
		val consumed = mutableSetOf<String>()
		val remaining = localEchoes.filter { echo ->
			// The sender returns only a Boolean, so match new outgoing messages conservatively:
			// text sends compare body text; media sends have no caption and match on type/source/thread.
			// Known IDs and history baselines protect pre-existing rows against clock skew.
			val match = serverMessages.firstOrNull {
				echo.completed &&
					(
						echo.item.localEchoStatus == ConnectorLocalEchoStatus.PENDING ||
							echo.item.localEchoStatus == ConnectorLocalEchoStatus.SENT
						) &&
					it.stableId !in echo.knownIds &&
					it.stableId !in consumed &&
					it.connectorSource == echo.item.connectorSource &&
					it.connectorThreadId == echo.item.connectorThreadId &&
					it.isOutgoing &&
					it.type == echo.item.type &&
					(echo.item.type != ConnectorMessageType.TEXT || it.text == echo.item.text) &&
					(echo.knownIds.isNotEmpty() || it.dateMillis / 1000 >= echo.item.dateMillis / 1000)
			}
			if (match != null) consumed.add(match.stableId)
			match == null
		}.map { it.copy(knownIds = it.knownIds + consumed) }
		return copy(messages = serverMessages + remaining.map { it.item }, localEchoes = remaining)
	}
}

internal data class ConnectorLocalEcho(
	val item: ConnectorConversationMessageItem,
	val knownIds: Set<String>,
	val completed: Boolean = false,
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
		localEchoes = previousState.localEchoes,
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
