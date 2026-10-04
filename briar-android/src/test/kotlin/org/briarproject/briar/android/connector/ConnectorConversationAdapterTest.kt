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

	@Test
	fun testDeleteAffordanceOnlyOnOwnSentMessages() {
		val context = RuntimeEnvironment.getApplication()
		val parent = android.widget.FrameLayout(context)
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, parent, false)
		parent.addView(view)
		
		var clickedItem: ConnectorConversationMessageItem? = null
		val holder = ConnectorConversationAdapter.MessageViewHolder(view) {
			clickedItem = it
		}

		// Received message: no action
		val received = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room",
			connectorMessageId = "\$1",
			dateMillis = 1000L,
			isOutgoing = false,
			text = "hello",
			type = ConnectorMessageType.TEXT,
		)
		holder.bind(received)
		assertEquals(false, view.performLongClick())
		assertEquals(null, clickedItem)

		// Pending local echo: no action
		val pending = received.copy(isOutgoing = true, localEchoStatus = ConnectorLocalEchoStatus.PENDING)
		holder.bind(pending)
		assertEquals(false, view.performLongClick())
		assertEquals(null, clickedItem)

		// Sent local echo: no action
		val sentEcho = received.copy(isOutgoing = true, localEchoStatus = ConnectorLocalEchoStatus.SENT)
		holder.bind(sentEcho)
		assertEquals(false, view.performLongClick())
		assertEquals(null, clickedItem)

		// Failed local echo: no action
		val failed = received.copy(isOutgoing = true, localEchoStatus = ConnectorLocalEchoStatus.FAILED)
		holder.bind(failed)
		assertEquals(false, view.performLongClick())
		assertEquals(null, clickedItem)

		// Deleted message placeholder: no action
		val deleted = received.copy(isOutgoing = true, type = ConnectorMessageType.DELETED)
		holder.bind(deleted)
		assertEquals(false, view.performLongClick())
		assertEquals(null, clickedItem)

		// Own sent message: triggers callback
		val sent = received.copy(isOutgoing = true)
		holder.bind(sent)
		assertEquals(true, view.performLongClick())
		assertEquals(sent, clickedItem)
	}
}
