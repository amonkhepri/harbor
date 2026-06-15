package org.briarproject.briar.android.connector

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorConversationMessageListTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun testFromMessagesSkipsBlankTextAndSortsByConnectorOrder() {
		val items = ConnectorConversationMessageItem.fromMessages(listOf(
			connectorMessage("30", 30, true, "new", 30L),
			connectorMessage("2", 20, false, "", 2L),
			connectorMessage("4", 40, false, " \t\n", 4L),
			connectorMessage("1", 10, false, "old", 1L),
			connectorMessage("20", 30, false, "same time", 20L),
		))

		assertEquals(
			listOf(
				connectorMessageItem("1", false, "old", 10000L),
				connectorMessageItem("20", false, "same time", 30000L),
				connectorMessageItem("30", true, "new", 30000L),
			),
			items,
		)
	}

	@Test
	fun testMessageListRendersConnectorItems() {
		val incoming = connectorMessageItem(
			messageId = "1",
			isOutgoing = false,
			text = "Incoming connector body",
		)
		val outgoing = connectorMessageItem(
			messageId = "2",
			isOutgoing = true,
			text = "Outgoing connector body",
		)

		renderMessageList(listOf(incoming, outgoing))

		assertTextCount("Incoming connector body")
		assertTextCount("Outgoing connector body")
		assertTextCount("Incoming")
		assertTextCount("Outgoing")
	}

	@Test
	fun testMessageListRendersEmptyState() {
		renderMessageList(emptyText = "No connector messages")

		assertTextCount("No connector messages")
	}

	private fun renderMessageList(
		messages: List<ConnectorConversationMessageItem> = emptyList(),
		emptyText: String? = null,
	) {
		val state = ConnectorConversationMessageListState(
			messages = messages,
			emptyText = emptyText,
		)
		composeRule.setContent {
			MaterialTheme { ConnectorConversationMessageList(state) }
		}
		composeRule.waitForIdle()
	}

	private fun assertTextCount(text: String, count: Int = 1) =
		composeRule.onAllNodesWithText(text).assertCountEquals(count)

	private fun connectorMessageItem(
		messageId: String,
		isOutgoing: Boolean,
		text: String,
		dateMillis: Long = 0L,
	): ConnectorConversationMessageItem =
		ConnectorConversationMessageItem(
			connectorSource = CONNECTOR_SOURCE,
			connectorThreadId = "thread-1",
			connectorMessageId = messageId,
			dateMillis = dateMillis,
			isOutgoing = isOutgoing,
			text = text,
		)

	private fun connectorMessage(
		messageId: String,
		dateSeconds: Int,
		isOutgoing: Boolean,
		text: String,
		sourceMessageOrder: Long,
	): ConnectorMessage =
		ConnectorMessage(
			source = CONNECTOR_SOURCE,
			threadId = "thread-1",
			messageId = messageId,
			dateSeconds = dateSeconds,
			isOutgoing = isOutgoing,
			text = text,
			sourceMessageOrder = sourceMessageOrder,
		)

	companion object {
		private val CONNECTOR_SOURCE = ConnectorSource("sample", "Sample")
	}
}
