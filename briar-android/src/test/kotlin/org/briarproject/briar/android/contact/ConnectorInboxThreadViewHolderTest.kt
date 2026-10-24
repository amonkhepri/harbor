package org.briarproject.briar.android.contact

import android.app.Application
import android.view.LayoutInflater
import android.widget.TextView
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider
import org.briarproject.briar.R
import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
			"\uFEFF",
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
	fun testNonBreakingSpaceOnlyTitleFallsBackToPlaceholder() {
		RuntimeEnvironment.getApplication().setTheme(R.style.BriarTheme)
		EmojiManager.install(GoogleEmojiProvider())
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_thread, null)
		val holder = ConnectorInboxThreadViewHolder(view)
		val titleView = view.findViewById<TextView>(R.id.connectorThreadTitle)

		// U+00A0 NBSP, U+2007 FIGURE SPACE, and U+202F NARROW NO-BREAK SPACE are
		// Character.isSpaceChar (category Zs) but not Character.isWhitespace, so
		// they previously fell through title.trim().isEmpty() too.
		val nonBreakingSpaceTitles = listOf(" ", " ", " ", "   ")
		for (nonBreakingSpaceTitle in nonBreakingSpaceTitles) {
			holder.bind(FakeConnectorInboxThreadItem(title = nonBreakingSpaceTitle)) { _, _ -> }
			assertEquals("Chat", titleView.text.toString())
		}
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

	@Test
	fun testMatrixPositiveUnreadRendersLocalizedBadgeAndAccessibility() {
		val holder = createHolder()

		holder.bind(matrixItem(3)) { _, _ -> }
		assertBadgeVisible(holder, "3", "3 unread messages")

		holder.bind(matrixItem(1)) { _, _ -> }
		assertBadgeVisible(holder, "1", "1 unread message")
	}

	@Test
	fun testMatrixZeroAndUnavailableUnreadHidesBadgeAndResetsAccessibility() {
		val holder = createHolder()

		holder.bind(matrixItem(0)) { _, _ -> }
		assertBadgeHidden(holder)

		holder.bind(matrixItem(null)) { _, _ -> }
		assertBadgeHidden(holder)
	}

	@Test
	fun testNonMatrixPositiveUnreadRetainsExistingPresentationWithoutBadge() {
		val holder = createHolder()

		holder.bind(telegramItem(5)) { _, _ -> }
		assertBadgeHidden(holder)
	}

	@Test
	fun testRecycledRowHasNoStaleBadgeOrAccessibilitySuffix() {
		val holder = createHolder()

		holder.bind(matrixItem(7)) { _, _ -> }
		assertBadgeVisible(holder, "7", "7 unread messages")

		holder.bind(telegramItem(null)) { _, _ -> }
		assertBadgeHidden(holder)

		holder.bind(matrixItem(0)) { _, _ -> }
		assertBadgeHidden(holder)
	}

	private fun createHolder(): ConnectorInboxThreadViewHolder {
		RuntimeEnvironment.getApplication().setTheme(R.style.BriarTheme)
		EmojiManager.install(GoogleEmojiProvider())
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_thread, null)
		return ConnectorInboxThreadViewHolder(view)
	}

	private fun matrixItem(unreadCount: Int?) = FakeConnectorInboxThreadItem(
		connectorSource = ConnectorSources.MATRIX,
		unreadCount = unreadCount,
	)

	private fun telegramItem(unreadCount: Int?) = FakeConnectorInboxThreadItem(
		connectorSource = ConnectorSources.TELEGRAM,
		unreadCount = unreadCount,
	)

	private fun assertBadgeHidden(holder: ConnectorInboxThreadViewHolder) {
		assertEquals(android.view.View.GONE, holder.unreadBadge.visibility)
		assertEquals("", holder.unreadBadge.text.toString())
		assertNull(holder.unreadBadge.contentDescription)
	}

	private fun assertBadgeVisible(
		holder: ConnectorInboxThreadViewHolder,
		text: String,
		contentDescription: String,
	) {
		assertEquals(android.view.View.VISIBLE, holder.unreadBadge.visibility)
		assertEquals(text, holder.unreadBadge.text.toString())
		assertEquals(contentDescription, holder.unreadBadge.contentDescription.toString())
	}
}
