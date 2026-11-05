package org.briarproject.briar.android.connector

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import org.briarproject.briar.R
import org.briarproject.briar.android.connector.ConnectorLocalEchoStatus.FAILED
import org.briarproject.briar.android.connector.ConnectorLocalEchoStatus.PENDING
import org.briarproject.briar.android.connector.ConnectorLocalEchoStatus.SENT
import org.briarproject.briar.api.connector.ConnectorAttachmentMetadata
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReactionSummary
import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

		// Sent message with non-$ connectorMessageId: triggers callback without connector-specific id gating
		val nonDollarId = received.copy(isOutgoing = true, connectorMessageId = "m.local.123")
		holder.bind(nonDollarId)
		assertEquals(true, view.performLongClick())
		assertEquals(nonDollarId, clickedItem)

		// Sent message from another connector (Telegram): triggers callback connector-neutrally
		val telegramSent = received.copy(
			connectorSource = ConnectorSources.TELEGRAM,
			connectorMessageId = "42",
			isOutgoing = true,
		)
		holder.bind(telegramSent)
		assertEquals(true, view.performLongClick())
		assertEquals(telegramSent, clickedItem)

		// Connector without delete capability: no action
		val holderNoDeleter = ConnectorConversationAdapter.MessageViewHolder(view, null)
		holderNoDeleter.bind(sent)
		assertEquals(false, view.performLongClick())
	}

	@Test
	fun testRetrySendAffordanceOnlyOnFailedOutgoingEchoes() {
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		var retryItem: ConnectorConversationMessageItem? = null
		val holder = ConnectorConversationAdapter.MessageViewHolder(
			view,
			onDeleteClicked = null,
			onRetrySendClicked = { retryItem = it },
		)
		val base = ConnectorConversationMessageItem(
			ConnectorSources.MATRIX,
			"!room",
			"$1",
			1000L,
			false,
			"hello",
			ConnectorMessageType.TEXT,
		)
		val nonRetry = listOf(
			base,
			base.copy(localEchoStatus = FAILED),
			base.copy(isOutgoing = true),
			base.copy(isOutgoing = true, localEchoStatus = PENDING),
			base.copy(isOutgoing = true, localEchoStatus = SENT),
			base.copy(isOutgoing = true, localEchoStatus = FAILED, type = ConnectorMessageType.PHOTO),
			base.copy(isOutgoing = true, localEchoStatus = FAILED, type = ConnectorMessageType.FILE),
		)
		nonRetry.forEach {
			holder.bind(it)
			assertFalse(view.performClick())
			assertNull(retryItem)
		}

		val failed = base.copy(isOutgoing = true, localEchoStatus = FAILED)
		holder.bind(failed)
		assertTrue(view.performClick())
		assertEquals(failed, retryItem)

		retryItem = null
		ConnectorConversationAdapter.MessageViewHolder(view).bind(failed)
		assertFalse(view.performClick())
		assertNull(retryItem)
	}

	@Test
	fun testFileMessageWithMetadataRendersBoundedAccessibleRow() {
		val context = RuntimeEnvironment.getApplication()
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)
		val item = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			connectorMessageId = "\$event:example.org",
			dateMillis = 1_000L,
			isOutgoing = false,
			text = "document",
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "quarterly_report.pdf",
				mimeType = "application/pdf",
				size = 1024L * 1024L,
			),
		)
		holder.bind(item)

		val fileLayout = view.findViewById<View>(R.id.connectorFileLayout)
		val fileName = view.findViewById<TextView>(R.id.connectorFileName)
		val fileDetails = view.findViewById<TextView>(R.id.connectorFileDetails)
		val text = view.findViewById<TextView>(R.id.connectorMessageText)

		assertEquals(View.VISIBLE, fileLayout.visibility)
		assertEquals("quarterly_report.pdf", fileName.text.toString())
		assertEquals(View.VISIBLE, fileDetails.visibility)
		assertTrue(fileDetails.text.toString().contains("application/pdf"))
		assertTrue(fileDetails.text.toString().contains("MB"))
		assertEquals(View.GONE, text.visibility)
		val desc = fileLayout.contentDescription.toString()
		assertTrue(desc.contains("quarterly_report.pdf"))
		assertTrue(desc.contains("application/pdf"))
	}

	@Test
	fun testFileMessageWithMissingNameFallsBackSafely() {
		val context = RuntimeEnvironment.getApplication()
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)
		val fallbackExpected = context.getString(R.string.connector_message_file)

		val missingName = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			connectorMessageId = "\$event:example.org",
			dateMillis = 1_000L,
			isOutgoing = false,
			text = "",
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = null,
				mimeType = "application/pdf",
				size = 500L,
			),
		)
		holder.bind(missingName)
		assertEquals(
			fallbackExpected,
			view.findViewById<TextView>(R.id.connectorFileName).text.toString(),
		)

		val whitespaceOnlyName = missingName.copy(
			attachment = ConnectorAttachmentMetadata(fileName = "   \t\n  "),
		)
		holder.bind(whitespaceOnlyName)
		assertEquals(
			fallbackExpected,
			view.findViewById<TextView>(R.id.connectorFileName).text.toString(),
		)
	}

	@Test
	fun testFileMessageWithOversizedAndMaliciousNameSanitized() {
		val context = RuntimeEnvironment.getApplication()
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)

		// Path traversal stripped
		val pathItem = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			connectorMessageId = "\$1",
			dateMillis = 1_000L,
			isOutgoing = false,
			text = "",
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "../../secret/passwords.txt",
				mimeType = "text/plain",
				size = 120L,
			),
		)
		holder.bind(pathItem)
		assertEquals("passwords.txt", view.findViewById<TextView>(R.id.connectorFileName).text.toString())

		// Control chars and bidi override stripped
		val maliciousItem = pathItem.copy(
			attachment = ConnectorAttachmentMetadata(
				fileName = "normal\u202Efdp.exe",
				mimeType = "application/octet-stream",
			),
		)
		holder.bind(maliciousItem)
		assertEquals("normalfdp.exe", view.findViewById<TextView>(R.id.connectorFileName).text.toString())

		// Unpaired surrogates stripped
		val loneSurrogateItem = pathItem.copy(
			attachment = ConnectorAttachmentMetadata(
				fileName = "file\uD800name\uDC00.txt",
				mimeType = "text/plain",
			),
		)
		holder.bind(loneSurrogateItem)
		assertEquals("filename.txt", view.findViewById<TextView>(R.id.connectorFileName).text.toString())

		// Oversized name bounded to MAX_FILENAME_LENGTH while preserving extension
		val longName = "a".repeat(150) + ".pdf"
		val oversizedItem = pathItem.copy(
			attachment = ConnectorAttachmentMetadata(
				fileName = longName,
				mimeType = "application/pdf",
			),
		)
		holder.bind(oversizedItem)
		val renderedName = view.findViewById<TextView>(R.id.connectorFileName).text.toString()
		assertTrue(renderedName.length <= ConnectorFileMetadataFormatter.MAX_FILENAME_LENGTH)
		assertTrue(renderedName.endsWith("….pdf"))

		// Oversized name with surrogate pair at cut boundary avoids dangling surrogate
		// available = 100 - 4 (.pdf) - 1 (…) = 95
		// 94 'a's + emoji surrogate pair (\uD83D\uDE00) -> cut at 95 lands on high surrogate
		val surrogateName = "a".repeat(94) + "\uD83D\uDE00" + "b".repeat(50) + ".pdf"
		val surrogateItem = pathItem.copy(
			attachment = ConnectorAttachmentMetadata(
				fileName = surrogateName,
				mimeType = "application/pdf",
			),
		)
		holder.bind(surrogateItem)
		val renderedSurrogateName = view.findViewById<TextView>(R.id.connectorFileName).text.toString()
		assertTrue(renderedSurrogateName.length <= ConnectorFileMetadataFormatter.MAX_FILENAME_LENGTH)
		assertTrue(renderedSurrogateName.endsWith("….pdf"))
		// Verify no dangling high surrogate at truncation point
		assertFalse(
			Character.isHighSurrogate(renderedSurrogateName[renderedSurrogateName.indexOf('…') - 1]),
		)
	}

	@Test
	fun testFileMessageWithUnavailableSizeRendersSafely() {
		val context = RuntimeEnvironment.getApplication()
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)

		// Size is null
		val noSize = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			connectorMessageId = "\$1",
			dateMillis = 1_000L,
			isOutgoing = false,
			text = "",
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "notes.txt",
				mimeType = "text/plain",
				size = null,
			),
		)
		holder.bind(noSize)
		val details = view.findViewById<TextView>(R.id.connectorFileDetails)
		assertEquals(View.VISIBLE, details.visibility)
		assertEquals("text/plain", details.text.toString())

		// Negative size treated as unavailable
		val negSize = noSize.copy(
			attachment = ConnectorAttachmentMetadata(
				fileName = "notes.txt",
				mimeType = "text/plain",
				size = -10L,
			),
		)
		holder.bind(negSize)
		assertEquals("text/plain", details.text.toString())

		// Neither mime nor size
		val noDetails = noSize.copy(
			attachment = ConnectorAttachmentMetadata(
				fileName = "notes.txt",
				mimeType = null,
				size = null,
			),
		)
		holder.bind(noDetails)
		assertEquals(View.GONE, details.visibility)
	}

	@Test
	fun testRecycledRowsResetFileStateAcrossRebinds() {
		val context = RuntimeEnvironment.getApplication()
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)

		val fileItem = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room",
			connectorMessageId = "\$file1",
			dateMillis = 1_000L,
			isOutgoing = false,
			text = "",
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "data.csv",
				mimeType = "text/csv",
				size = 2048L,
			),
		)

		val textItem = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room",
			connectorMessageId = "\$text1",
			dateMillis = 2_000L,
			isOutgoing = true,
			text = "Plain message",
			type = ConnectorMessageType.TEXT,
		)

		val deletedItem = textItem.copy(
			connectorMessageId = "\$del1",
			type = ConnectorMessageType.DELETED,
		)

		val emptyFileItem = fileItem.copy(
			connectorMessageId = "\$file2",
			attachment = null,
		)

		val fileItemWithReactions = fileItem.copy(
			connectorMessageId = "\$file3",
			reactions = listOf(ConnectorReactionSummary("🔥", 5)),
		)

		val fileLayout = view.findViewById<View>(R.id.connectorFileLayout)
		val fileName = view.findViewById<TextView>(R.id.connectorFileName)
		val fileDetails = view.findViewById<TextView>(R.id.connectorFileDetails)
		val text = view.findViewById<TextView>(R.id.connectorMessageText)

		// 1. Bind file item
		holder.bind(fileItem)
		assertEquals(View.VISIBLE, fileLayout.visibility)
		assertEquals("data.csv", fileName.text.toString())
		assertEquals(View.VISIBLE, fileDetails.visibility)
		assertEquals(View.GONE, text.visibility)
		assertTrue(fileLayout.contentDescription != null)

		// 2. Rebind same ViewHolder to text item (recycling test)
		holder.bind(textItem)
		assertEquals(View.GONE, fileLayout.visibility)
		assertNull(fileLayout.contentDescription)
		assertEquals("", fileName.text.toString())
		assertEquals("", fileDetails.text.toString())
		assertEquals(View.GONE, fileDetails.visibility)
		assertEquals(View.VISIBLE, text.visibility)
		assertEquals("Plain message", text.text.toString())

		// 3. Rebind to file item with missing metadata: ensures previous file's metadata does not leak
		holder.bind(emptyFileItem)
		assertEquals(View.VISIBLE, fileLayout.visibility)
		assertEquals(context.getString(R.string.connector_message_file), fileName.text.toString())
		assertEquals("", fileDetails.text.toString())
		assertEquals(View.GONE, fileDetails.visibility)
		assertEquals(View.GONE, text.visibility)

		// 4. Rebind to deleted message
		holder.bind(deletedItem)
		assertEquals(View.GONE, fileLayout.visibility)
		assertNull(fileLayout.contentDescription)
		assertEquals(View.VISIBLE, text.visibility)
		assertEquals(context.getString(R.string.connector_message_deleted), text.text.toString())

		// 5. Rebind to file item with reactions
		holder.bind(fileItemWithReactions)
		assertEquals(View.VISIBLE, fileLayout.visibility)
		assertEquals("data.csv", fileName.text.toString())
		assertEquals(View.VISIBLE, text.visibility)
		assertEquals("🔥 5", text.text.toString())
		assertTrue(fileLayout.contentDescription.toString().contains("🔥 5"))

		// 6. Rebind back to file item without reactions: ensures reactions view resets to GONE and empty
		holder.bind(fileItem)
		assertEquals(View.VISIBLE, fileLayout.visibility)
		assertEquals("data.csv", fileName.text.toString())
		assertEquals(View.GONE, text.visibility)
		assertEquals("", text.text.toString())
		assertFalse(fileLayout.contentDescription.toString().contains("🔥 5"))
	}

	@Test
	fun testFileMessageWithReactionsRendersReactionsAndAccessibility() {
		val context = RuntimeEnvironment.getApplication()
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)

		val item = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			connectorMessageId = "\$file:example.org",
			dateMillis = 1_000L,
			isOutgoing = false,
			text = "",
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "report.pdf",
				mimeType = "application/pdf",
				size = 1024L * 1024L,
			),
			reactions = listOf(
				ConnectorReactionSummary("👍", 2),
				ConnectorReactionSummary("🎉", 1),
			),
		)
		holder.bind(item)

		val fileLayout = view.findViewById<View>(R.id.connectorFileLayout)
		val fileName = view.findViewById<TextView>(R.id.connectorFileName)
		val fileDetails = view.findViewById<TextView>(R.id.connectorFileDetails)
		val text = view.findViewById<TextView>(R.id.connectorMessageText)

		assertEquals(View.VISIBLE, fileLayout.visibility)
		assertEquals("report.pdf", fileName.text.toString())
		assertEquals(View.VISIBLE, fileDetails.visibility)
		assertEquals(View.VISIBLE, text.visibility)
		assertEquals("👍 2  🎉 1", text.text.toString())
		val desc = fileLayout.contentDescription.toString()
		assertTrue(desc.contains("report.pdf"))
		assertTrue(desc.contains("application/pdf"))
		assertTrue(desc.contains("👍 2  🎉 1"))
	}

	@Test
	fun testFileMessageWithReplyAndEditedStateRendersInFileNameAndAccessibility() {
		val context = RuntimeEnvironment.getApplication()
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)

		val item = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			connectorMessageId = "\$file:example.org",
			dateMillis = 1_000L,
			isOutgoing = false,
			isReply = true,
			isEdited = true,
			text = "",
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "notes.txt",
				mimeType = "text/plain",
				size = 512L,
			),
			reactions = listOf(ConnectorReactionSummary("❤️", 1)),
		)
		holder.bind(item)

		val fileLayout = view.findViewById<View>(R.id.connectorFileLayout)
		val fileName = view.findViewById<TextView>(R.id.connectorFileName)
		val text = view.findViewById<TextView>(R.id.connectorMessageText)

		assertEquals(View.VISIBLE, fileLayout.visibility)
		val expectedReply = context.getString(R.string.connector_message_reply, "notes.txt")
		val expectedEdited = context.getString(R.string.connector_message_edited, expectedReply)
		assertEquals(expectedEdited, fileName.text.toString())
		assertEquals(View.VISIBLE, text.visibility)
		assertEquals("❤️ 1", text.text.toString())

		val desc = fileLayout.contentDescription.toString()
		assertTrue(desc.contains(expectedEdited))
		assertTrue(desc.contains("text/plain"))
		assertTrue(desc.contains("❤️ 1"))
	}

	@Test
	fun testFileMessageRendersExplicitDownloadState() {
		val context = RuntimeEnvironment.getApplication()
		val view = LayoutInflater.from(context)
			.inflate(R.layout.list_item_connector_message, null)
		var clickedItem: ConnectorConversationMessageItem? = null
		val holder = ConnectorConversationAdapter.MessageViewHolder(
			itemView = view,
			onDeleteClicked = null,
			onRetrySendClicked = null,
			onFileClicked = { clickedItem = it },
		)

		val baseItem = ConnectorConversationMessageItem(
			connectorSource = ConnectorSources.MATRIX,
			connectorThreadId = "!room:example.org",
			connectorMessageId = "\$msg1",
			dateMillis = 1_000L,
			isOutgoing = false,
			text = "",
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "archive.zip",
				mimeType = "application/zip",
				size = 1024L,
			),
		)

		// 1. Idle state: file layout is clickable and invokes onFileClicked
		holder.bind(baseItem)
		val fileLayout = view.findViewById<View>(R.id.connectorFileLayout)
		val fileDetails = view.findViewById<TextView>(R.id.connectorFileDetails)
		assertTrue(fileLayout.isClickable)
		fileLayout.performClick()
		assertEquals(baseItem, clickedItem)
		assertFalse(fileDetails.text.toString().contains("Downloading"))

		// 2. Downloading state: renders downloading status
		val downloadingItem = baseItem.copy(downloadState = ConnectorDownloadState.DOWNLOADING)
		holder.bind(downloadingItem)
		val downloadingText = context.getString(R.string.connector_file_downloading)
		assertTrue(fileDetails.text.toString().contains(downloadingText))
		assertTrue(fileLayout.contentDescription.toString().contains(downloadingText))

		// 3. Downloaded state: renders downloaded status
		val downloadedItem = baseItem.copy(downloadState = ConnectorDownloadState.DOWNLOADED)
		holder.bind(downloadedItem)
		val downloadedText = context.getString(R.string.connector_file_downloaded)
		assertTrue(fileDetails.text.toString().contains(downloadedText))
		assertTrue(fileLayout.contentDescription.toString().contains(downloadedText))

		// 4. Failed state: renders download failed status
		val failedItem = baseItem.copy(downloadState = ConnectorDownloadState.FAILED)
		holder.bind(failedItem)
		val failedText = context.getString(R.string.connector_file_download_failed)
		assertTrue(fileDetails.text.toString().contains(failedText))
		assertTrue(fileLayout.contentDescription.toString().contains(failedText))
	}
}
