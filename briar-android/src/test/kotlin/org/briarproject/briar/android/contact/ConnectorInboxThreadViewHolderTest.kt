package org.briarproject.briar.android.contact

import android.app.Application
import android.view.LayoutInflater
import android.widget.TextView
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider
import org.briarproject.briar.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorInboxThreadViewHolderTest {

	@Test
	fun testInvisibleUnicodeOnlyTitleFallsBackToPlaceholder() {
		RuntimeEnvironment.getApplication().setTheme(R.style.BriarTheme)
		EmojiManager.install(GoogleEmojiProvider())
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_thread, null)
		val holder = ConnectorInboxThreadViewHolder(view)
		val titleView = view.findViewById<TextView>(R.id.connectorThreadTitle)

		val invisibleTitles = listOf(
			"​",
			"﻿",
			"‎‏",
			"⁠",
			"​﻿‎‏⁠",
		)
		for (invisibleTitle in invisibleTitles) {
			holder.bind(FakeConnectorInboxThreadItem(title = invisibleTitle)) { _, _ -> }
			assertEquals("Chat", titleView.text.toString())
		}

		holder.bind(FakeConnectorInboxThreadItem(title = "​Custom Room")) { _, _ -> }
		assertEquals("​Custom Room", titleView.text.toString())
	}

	@Test
	fun testSurrogatePairEmojiOnlyTitleIsNotTreatedAsInvisible() {
		RuntimeEnvironment.getApplication().setTheme(R.style.BriarTheme)
		EmojiManager.install(GoogleEmojiProvider())
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_thread, null)
		val holder = ConnectorInboxThreadViewHolder(view)
		val titleView = view.findViewById<TextView>(R.id.connectorThreadTitle)

		// U+1F600 GRINNING FACE is encoded as a UTF-16 surrogate pair; each half
		// is individually classified as Character.SURROGATE, so a code-unit-wise
		// blank check would wrongly treat this visible title as invisible-only.
		holder.bind(FakeConnectorInboxThreadItem(title = "😀")) { _, _ -> }
		assertEquals("😀", titleView.text.toString())
	}
}
