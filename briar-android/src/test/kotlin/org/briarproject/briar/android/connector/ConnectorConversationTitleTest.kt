package org.briarproject.briar.android.connector

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorConversationTitleTest {
	@Test
	fun invisibleTitlesFallBackAndVisibleTitlesRemainUnchanged() {
		val invisible = listOf(
			null, "", " \t\n", "\u00a0\u2007\u202f", "\u0000\u0007",
			"\u200b", "\ufeff", "\u200e\u200f", "\u2060", " \u200b\u0000\ufeff ",
		)
		val visible = listOf("Room title", "😀", "👩‍💻", "\u200eRoom\u200f", "  Room  ")
		for (source in listOf("Matrix", "Telegram")) {
			val fallback = "$source conversation"
			for (title in invisible) {
				assertEquals(fallback, ConnectorConversationActivity.titleText(title, fallback))
			}
			for (title in visible) {
				assertEquals(title, ConnectorConversationActivity.titleText(title, fallback))
			}
		}
	}
}
