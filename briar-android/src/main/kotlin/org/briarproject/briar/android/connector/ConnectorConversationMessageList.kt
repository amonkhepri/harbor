package org.briarproject.briar.android.connector

import org.briarproject.briar.android.connector.ConnectorLocalEchoStatus.PENDING
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
		val confirmed = messages.filter { it.localEchoStatus == null }
		return copy(
			messages = messages + item,
			isLoading = false,
			localEchoes = localEchoes + ConnectorLocalEcho(
				item,
				confirmed.map { it.stableId }.toSet(),
				historyBaseline = confirmed.maxOfOrNull { it.dateMillis },
			),
		)
	}

	fun completeSend(id: String, sent: Boolean): ConnectorConversationMessageListState {
		val status = if (sent) ConnectorLocalEchoStatus.SENT else ConnectorLocalEchoStatus.FAILED
		val echoes = localEchoes.map {
			if (it.item.stableId != id) {
				it
			} else {
				it.copy(completed = true, item = it.item.copy(localEchoStatus = status))
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

	fun retrySend(id: String): ConnectorConversationMessageListState {
		val echoes = localEchoes.map { echo ->
			if (echo.item.stableId != id) {
				echo
			} else {
				echo.copy(completed = false, item = echo.item.copy(localEchoStatus = PENDING))
			}
		}
		return copy(
			localEchoes = echoes,
			messages = messages.map { m ->
				echoes.firstOrNull { it.item.stableId == m.stableId }?.item ?: m
			},
		)
	}

	fun updateDownloadState(
		id: String,
		state: ConnectorDownloadState,
		file: java.io.File? = null,
	): ConnectorConversationMessageListState = copy(
		messages = messages.map { message ->
			if (message.stableId == id) {
				message.copy(downloadState = state, downloadedFile = file)
			} else {
				message
			}
		},
	)

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
					echo.item.localEchoStatus == ConnectorLocalEchoStatus.SENT &&
					it.stableId !in echo.knownIds &&
					it.stableId !in consumed &&
					it.connectorSource == echo.item.connectorSource &&
					it.connectorThreadId == echo.item.connectorThreadId &&
					it.isOutgoing &&
					it.type == echo.item.type &&
					(echo.item.type != ConnectorMessageType.TEXT || it.text == echo.item.text) &&
					(
						it.dateMillis / 1000 >= echo.item.dateMillis / 1000 ||
							(echo.historyBaseline != null && it.dateMillis >= echo.historyBaseline)
						)
			}
			if (match != null) consumed.add(match.stableId)
			match == null
		}.map { it.copy(knownIds = it.knownIds + consumed) }
		val downloadStates = messages.associate {
			it.stableId to Pair(it.downloadState, it.downloadedFile)
		}
		val mergedServerMessages = serverMessages.map { item ->
			val statePair = downloadStates[item.stableId]
			if (statePair != null && statePair.first != null) {
				item.copy(downloadState = statePair.first, downloadedFile = statePair.second)
			} else {
				item
			}
		}
		val remainingItems = remaining.map { echo ->
			val statePair = downloadStates[echo.item.stableId]
			if (statePair != null && statePair.first != null) {
				echo.item.copy(downloadState = statePair.first, downloadedFile = statePair.second)
			} else {
				echo.item
			}
		}
		return copy(messages = mergedServerMessages + remainingItems, localEchoes = remaining)
	}
}

internal data class ConnectorLocalEcho(
	val item: ConnectorConversationMessageItem,
	val knownIds: Set<String>,
	val completed: Boolean = false,
	val historyBaseline: Long? = null,
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
