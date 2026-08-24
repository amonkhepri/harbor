package org.briarproject.briar.android.connector

import android.app.Application
import android.view.LayoutInflater
import android.widget.TextView
import org.briarproject.briar.R
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReactionSummary
import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorConversationAdapterTest {

	@Test
	fun testEditedMessageRendersVisibleMarker() {
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)
		holder.bind(
			ConnectorConversationMessageItem(
				connectorSource = ConnectorSources.MATRIX,
				connectorThreadId = "!room:example.org",
				connectorMessageId = "\$event:example.org",
				dateMillis = 1_000L,
				isOutgoing = false,
				text = "updated body",
				type = ConnectorMessageType.TEXT,
				isEdited = true,
			),
		)

		assertEquals(
			"updated body (edited)",
			view.findViewById<TextView>(R.id.connectorMessageText).text.toString(),
		)
	}

	@Test
	fun testReplyMessageRendersVisibleMarker() {
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)
		holder.bind(
			ConnectorConversationMessageItem(
				connectorSource = ConnectorSources.MATRIX,
				connectorThreadId = "!room:example.org",
				connectorMessageId = "\$event:example.org",
				dateMillis = 1_000L,
				isOutgoing = false,
				text = "reply body",
				type = ConnectorMessageType.TEXT,
				isReply = true,
			),
		)

		assertEquals(
			"↩ reply body",
			view.findViewById<TextView>(R.id.connectorMessageText).text.toString(),
		)
	}

	@Test
	fun testReactionSummariesRenderKeysAndAggregateCounts() {
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)
		holder.bind(
			ConnectorConversationMessageItem(
				connectorSource = ConnectorSources.MATRIX,
				connectorThreadId = "!room:example.org",
				connectorMessageId = "\$event:example.org",
				dateMillis = 1_000L,
				isOutgoing = false,
				text = "body",
				type = ConnectorMessageType.TEXT,
				reactions = listOf(
					ConnectorReactionSummary("👍", 2),
					ConnectorReactionSummary("😂", 1),
				),
			),
		)

		assertEquals(
			"body\n👍 2  😂 1",
			view.findViewById<TextView>(R.id.connectorMessageText).text.toString(),
		)
	}
}
