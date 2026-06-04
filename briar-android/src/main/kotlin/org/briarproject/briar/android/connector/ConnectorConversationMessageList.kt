package org.briarproject.briar.android.connector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import org.briarproject.briar.R

internal const val CONNECTOR_CONVERSATION_MESSAGE_LIST_TAG =
	"connector_conversation_message_list"
internal const val CONNECTOR_CONVERSATION_MESSAGE_ROW_TAG_PREFIX =
	"connector_conversation_message_row:"

internal data class ConnectorConversationMessageListState(
	val messages: List<ConnectorConversationMessageItem>,
	val emptyText: String? = null,
)

internal enum class ConnectorConversationAvailabilityState {
	LOADING, DISABLED, ACCOUNT_UNAVAILABLE, EMPTY, LOAD_FAILED
}

internal fun connectorConversationAvailabilityState(
	connectorEnabled: Boolean,
	authorized: Boolean,
	loadFailed: Boolean,
	loadPending: Boolean,
): ConnectorConversationAvailabilityState = when {
	loadPending -> ConnectorConversationAvailabilityState.LOADING
	!connectorEnabled -> ConnectorConversationAvailabilityState.DISABLED
	!authorized -> ConnectorConversationAvailabilityState.ACCOUNT_UNAVAILABLE
	loadFailed -> ConnectorConversationAvailabilityState.LOAD_FAILED
	else -> ConnectorConversationAvailabilityState.EMPTY
}

@Composable
internal fun ConnectorConversationMessageList(
	state: ConnectorConversationMessageListState,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier = modifier.testTag(CONNECTOR_CONVERSATION_MESSAGE_LIST_TAG),
	) {
		val messages = state.messages
		val emptyText = state.emptyText
		if (messages.isEmpty() && emptyText != null) {
			item {
				Text(
					text = emptyText,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onBackground,
					modifier = Modifier.padding(
						horizontal = dimensionResource(
							R.dimen.margin_activity_horizontal),
						vertical = dimensionResource(R.dimen.margin_medium),
					),
				)
			}
		} else {
			items(
				items = messages,
				key = { it.stableId },
			) { item ->
				ConnectorConversationMessageRow(item)
			}
		}
	}
}

@Composable
private fun ConnectorConversationMessageRow(
	item: ConnectorConversationMessageItem,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(
				horizontal = dimensionResource(R.dimen.margin_activity_horizontal),
				vertical = dimensionResource(R.dimen.margin_medium),
			)
			.testTag(CONNECTOR_CONVERSATION_MESSAGE_ROW_TAG_PREFIX + item.stableId),
	) {
		Text(
			text = item.text,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onBackground,
		)
		Row(
			modifier = Modifier.padding(top = dimensionResource(R.dimen.margin_tiny)),
		) {
			Text(
				text = stringResource(directionText(item)),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			val dateText = dateText(LocalContext.current, item).toString()
			if (dateText.isNotEmpty()) {
				Text(
					text = dateText,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(
						start = dimensionResource(R.dimen.margin_small),
					),
				)
			}
		}
	}
}
