package org.briarproject.briar.android.connector

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import org.briarproject.briar.api.connector.ConnectorSource
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

		composeRule.setContent {
			MaterialTheme {
				ConnectorConversationMessageList(listOf(incoming, outgoing))
			}
		}
		composeRule.waitForIdle()

		composeRule.onNodeWithTag(CONNECTOR_CONVERSATION_MESSAGE_LIST_TAG)
			.assertIsDisplayed()
		composeRule.onNodeWithTag(
			CONNECTOR_CONVERSATION_MESSAGE_ROW_TAG_PREFIX + incoming.stableId,
		).assertIsDisplayed()
		composeRule.onNodeWithTag(
			CONNECTOR_CONVERSATION_MESSAGE_ROW_TAG_PREFIX + outgoing.stableId,
		).assertIsDisplayed()
		composeRule.onAllNodesWithText("Incoming connector body")
			.assertCountEquals(1)
		composeRule.onAllNodesWithText("Outgoing connector body")
			.assertCountEquals(1)
		composeRule.onAllNodesWithText("Incoming").assertCountEquals(1)
		composeRule.onAllNodesWithText("Outgoing").assertCountEquals(1)
	}

	private fun connectorMessageItem(
		messageId: String,
		isOutgoing: Boolean,
		text: String,
	): ConnectorConversationMessageItem =
		ConnectorConversationMessageItem(
			connectorSource = CONNECTOR_SOURCE,
			connectorThreadId = "thread-1",
			connectorMessageId = messageId,
			dateMillis = 0L,
			isOutgoing = isOutgoing,
			text = text,
		)

	companion object {
		private val CONNECTOR_SOURCE = ConnectorSource("sample", "Sample")
	}
}
