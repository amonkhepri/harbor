package org.briarproject.briar.android.connector

import androidx.appcompat.app.ActionBar
import org.mockito.Mockito.mock
import org.mockito.Mockito.inOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectorConversationActivityTest {
	@Test
	fun topicSubtitleNormalizesWhitespaceAndRemovesControls() {
		assertEquals(
			"Room purpose here",
			ConnectorConversationActivity.topicSubtitle(
				"  Room\n\tpurpose\u00a0here \r\n",
			),
		)
		assertEquals("Room", ConnectorConversationActivity.topicSubtitle("\u0000Ro\u0007om\u202e"))
		for (topic in listOf(null, "", " \n\t", "\u0000\u200b")) {
			assertNull(ConnectorConversationActivity.topicSubtitle(topic))
		}
	}

	@Test
	fun topicSubtitleTruncatesWithoutSplittingEmoji() {
		assertEquals(
			"😀".repeat(119) + "…",
			ConnectorConversationActivity.topicSubtitle("😀".repeat(121)),
		)
		assertEquals("a".repeat(120), ConnectorConversationActivity.topicSubtitle("a".repeat(120)))
	}

	@Test
	fun topicSubtitleTracksRoomChangesAndClearsMissingTopics() {
		val bar = mock(ActionBar::class.java)
		for (topic in listOf("First room", "Second\nroom", null, "")) {
			ConnectorConversationActivity.bindTopic(bar, topic)
		}
		val ordered = inOrder(bar)
		ordered.verify(bar).setSubtitle("First room")
		ordered.verify(bar).setSubtitle("Second room")
		ordered.verify(bar, org.mockito.Mockito.times(2)).setSubtitle(null as CharSequence?)
	}
}
