package org.briarproject.briar.android.connector

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.briarproject.briar.R
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.android.view.BriarRecyclerView
import org.briarproject.briar.api.connector.ConnectorAttachmentMetadata
import org.briarproject.briar.api.connector.ConnectorFileDownloader
import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorRoomObserver
import org.briarproject.briar.api.connector.ConnectorRoomObserver.RoomSubscription
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorTextSender
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorConversationRemoteRefreshTest {

	@get:Rule val rule = InstantTaskExecutorRule()

	@org.junit.Before
	fun setUp() {
		resetFileProviderCache()
	}

	@org.junit.After
	fun tearDown() {
		resetFileProviderCache()
	}

	private fun resetFileProviderCache() {
		try {
			val sCacheField = FileProvider::class.java.getDeclaredField("sCache")
			sCacheField.isAccessible = true
			(sCacheField.get(null) as? MutableMap<*, *>)?.clear()
		} catch (ignored: Exception) {}
	}

	private class FakeConnector :
		ReadOnlyConnector,
		ConnectorTextSender,
		ConnectorRoomObserver,
		ConnectorFileDownloader {
		override val source = ConnectorSources.MATRIX
		override fun isEnabled() = true
		override fun isAuthorized() = true
		override fun getRecentThreads(limit: Int) = emptyList<ConnectorThread>()

		val messages = mutableListOf<ConnectorMessage>()
		var downloadFailure: ConnectorFileDownloader.FailureCause? = null

		override fun getRecentMessageReadResult(threadId: String, limit: Int) =
			ConnectorMessageReadResult.Success(messages.toList())

		override fun sendText(threadId: String, text: String) = true

		override fun downloadFile(
			threadId: String,
			messageId: String,
			attachment: ConnectorAttachmentMetadata,
			destinationDir: File,
			isCancelled: () -> Boolean,
		): ConnectorFileDownloader.DownloadResult {
			if (isCancelled()) {
				return ConnectorFileDownloader.DownloadResult.Failure(
					ConnectorFileDownloader.FailureCause.CANCELLED,
				)
			}
			downloadFailure?.let { return ConnectorFileDownloader.DownloadResult.Failure(it) }
			val file = File(destinationDir, "downloaded_" + (attachment.fileName ?: "file"))
			file.writeText("sample content")
			return ConnectorFileDownloader.DownloadResult.Success(file)
		}

		var activeSubscription: FakeSubscription? = null

		override fun observeRoom(threadId: String, onUpdate: () -> Unit): RoomSubscription {
			val sub = FakeSubscription(onUpdate)
			activeSubscription = sub
			return sub
		}

		class FakeSubscription(val onUpdate: () -> Unit) : RoomSubscription {
			var cancelled = false
			override fun cancel() {
				cancelled = true
			}
			override fun close() = cancel()
		}
	}

	private val fakeConnector = FakeConnector()
	private val work = ArrayDeque<Runnable>()

	private fun drainWork() {
		ShadowLooper.idleMainLooper()
		while (work.isNotEmpty()) {
			work.removeFirst().run()
			ShadowLooper.idleMainLooper()
		}
	}

	private fun createActivity(
		threadId: String = "!room1:matrix.org",
	): ConnectorConversationActivity {
		Localizer.initialize(RuntimeEnvironment.getApplication().getSharedPreferences("test", 0))
		val intent = Intent().apply {
			putExtra(ConnectorConversationActivity.SOURCE_ID, ConnectorSources.MATRIX_ID)
			putExtra(ConnectorConversationActivity.THREAD_ID, threadId)
		}
		val activity = Robolectric.buildActivity(ConnectorConversationActivity::class.java, intent).get()
		activity.setTheme(R.style.BriarTheme)

		val root = FrameLayout(activity).apply { id = android.R.id.content }
		val list = BriarRecyclerView(activity)
		root.addView(
			list,
			FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT,
			),
		)
		val manager = LinearLayoutManager(activity).apply { stackFromEnd = true }
		list.setLayoutManager(manager)

		val adapter = ConnectorConversationActivity::class.java.getDeclaredField("adapter").apply {
			isAccessible = true
		}.get(activity) as ConnectorConversationAdapter
		list.setAdapter(adapter)

		val composeText = ConnectorComposeText(activity)
		setField(activity, "list", list)
		setField(activity, "layoutManager", manager)
		setField(activity, "composeText", composeText)
		setField(activity, "composeBar", root)
		setField(activity, "sendButton", Button(activity))
		setField(activity, "attachButton", ImageButton(activity))
		setField(activity, "fileButton", ImageButton(activity))
		setField(activity, "findBar", FrameLayout(activity))
		setField(activity, "findQuery", android.widget.EditText(activity))
		setField(activity, "findCount", android.widget.TextView(activity))
		setField(activity, "findPrevious", ImageButton(activity))
		setField(activity, "findNext", ImageButton(activity))
		setField(activity, "connector", fakeConnector)
		setField(activity, "textSender", fakeConnector)
		setField(activity, "sourceName", fakeConnector.source.displayName)
		setField(activity, "threadId", threadId)
		activity.ioExecutor = Executor { work.add(it) }
		return activity
	}

	private fun setField(target: Any, name: String, value: Any?) {
		var current: Class<*>? = target.javaClass
		while (current != null) {
			try {
				val field = current.getDeclaredField(name)
				field.isAccessible = true
				field.set(target, value)
				return
			} catch (ignored: NoSuchFieldException) {
				current = current.superclass
			}
		}
		error("Field $name not found on ${target.javaClass}")
	}

	private fun start(activity: ConnectorConversationActivity) {
		activity.startRoomObserver()
	}

	private fun destroy(activity: ConnectorConversationActivity) {
		activity.stopRoomObserver()
		activity.clearOwnedDownloads()
	}

	private fun messageState(
		activity: ConnectorConversationActivity,
	): ConnectorConversationMessageListState =
		ConnectorConversationActivity::class.java.getDeclaredField("messageState").apply {
			isAccessible = true
		}.get(activity) as ConnectorConversationMessageListState

	private fun layoutRecyclerView(
		activity: ConnectorConversationActivity,
		width: Int = 1080,
		height: Int = 800,
	) {
		val listField = ConnectorConversationActivity::class.java.getDeclaredField("list").apply {
			isAccessible = true
		}
		val list = listField.get(activity) as BriarRecyclerView
		list.measure(
			View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
		)
		list.layout(0, 0, width, height)
	}

	private fun getRecyclerView(activity: ConnectorConversationActivity): RecyclerView {
		val listField = ConnectorConversationActivity::class.java.getDeclaredField("list").apply {
			isAccessible = true
		}
		val list = listField.get(activity) as BriarRecyclerView
		return list.recyclerView
	}

	private fun getLayoutManager(activity: ConnectorConversationActivity): LinearLayoutManager {
		val managerField = ConnectorConversationActivity::class.java.getDeclaredField(
			"layoutManager",
		).apply {
			isAccessible = true
		}
		return managerField.get(activity) as LinearLayoutManager
	}

	private fun getAdapter(activity: ConnectorConversationActivity): ConnectorConversationAdapter {
		val adapterField = ConnectorConversationActivity::class.java.getDeclaredField("adapter").apply {
			isAccessible = true
		}
		return adapterField.get(activity) as ConnectorConversationAdapter
	}

	private fun connectorMessage(
		id: String,
		seconds: Int,
		text: String,
		isEdited: Boolean = false,
	): ConnectorMessage = ConnectorMessage(
		source = ConnectorSources.MATRIX,
		threadId = "!room1:matrix.org",
		messageId = id,
		dateSeconds = seconds,
		isOutgoing = false,
		text = text,
		sourceMessageOrder = seconds.toLong(),
		type = ConnectorMessageType.TEXT,
		isEdited = isEdited,
	)

	@Test
	fun remoteSendAppearsOnceWhileRoomIsOpen() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val sub = fakeConnector.activeSubscription
		assertNotNull(sub)
		assertFalse(sub!!.cancelled)
		assertEquals(0, messageState(activity).messages.size)

		// A remote message arrives
		fakeConnector.messages.add(
			ConnectorMessage(
				source = ConnectorSources.MATRIX,
				threadId = "!room1:matrix.org",
				messageId = "\$event1",
				dateSeconds = 1000,
				isOutgoing = false,
				text = "Hello from remote",
				sourceMessageOrder = 1000L,
				type = ConnectorMessageType.TEXT,
			),
		)

		// Fire observer callback
		sub.onUpdate()
		drainWork()

		// Message appears once
		val messages1 = messageState(activity).messages
		assertEquals(1, messages1.size)
		assertEquals("Hello from remote", messages1.single().text)

		// Multiple updates coalesce without duplicates
		fakeConnector.messages.add(
			ConnectorMessage(
				source = ConnectorSources.MATRIX,
				threadId = "!room1:matrix.org",
				messageId = "\$event2",
				dateSeconds = 1001,
				isOutgoing = false,
				text = "Second message",
				sourceMessageOrder = 1001L,
				type = ConnectorMessageType.TEXT,
			),
		)
		sub.onUpdate()
		sub.onUpdate() // second rapid update coalesces
		drainWork()

		val messages2 = messageState(activity).messages
		assertEquals(2, messages2.size)
		assertEquals("Hello from remote", messages2[0].text)
		assertEquals("Second message", messages2[1].text)
	}

	@Test
	fun screenDestructionCancelsObserverAndStopsCallbacks() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val sub = fakeConnector.activeSubscription
		assertNotNull(sub)
		assertFalse(sub!!.cancelled)

		destroy(activity)
		assertTrue(sub.cancelled)

		// Remote update after onDestroy must not mutate messageState
		assertEquals(0, messageState(activity).messages.size)

		fakeConnector.messages.add(
			ConnectorMessage(
				source = ConnectorSources.MATRIX,
				threadId = "!room1:matrix.org",
				messageId = "\$event3",
				dateSeconds = 1002,
				isOutgoing = false,
				text = "Should not appear",
				sourceMessageOrder = 1002L,
				type = ConnectorMessageType.TEXT,
			),
		)
		sub.onUpdate()
		drainWork()

		assertEquals(0, messageState(activity).messages.size)
	}

	private fun awaitAdapterUpdate(
		adapter: ConnectorConversationAdapter,
		expectedCount: Int,
		timeoutMs: Long = 2000,
	) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (adapter.itemCount != expectedCount && System.currentTimeMillis() < deadline) {
			Thread.sleep(10)
			ShadowLooper.idleMainLooper()
		}
		assertEquals(expectedCount, adapter.itemCount)
	}

	@Test
	fun loadMultiplePagesWithOverlappingEventsAndRemoteArrivalsPreservesAnchor() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val sub = fakeConnector.activeSubscription
		assertNotNull(sub)

		// 1. Initial snapshot: 15 messages (event10 to event24)
		for (i in 10..24) {
			fakeConnector.messages.add(
				connectorMessage("\$event$i", 1000 + i, "Message $i"),
			)
		}
		sub!!.onUpdate()
		drainWork()

		val manager = getLayoutManager(activity)
		val rv = getRecyclerView(activity)
		val adapter = getAdapter(activity)
		awaitAdapterUpdate(adapter, 15)
		layoutRecyclerView(activity)

		// Scroll to event11 with a specific pixel offset
		val initialAnchorIndex = adapter.currentList.indexOfFirst { it.connectorMessageId == "\$event11" }
		assertEquals(1, initialAnchorIndex)
		val expectedOffset = 0
		manager.scrollToPositionWithOffset(initialAnchorIndex, expectedOffset)
		layoutRecyclerView(activity)

		val initialVisiblePos = manager.findFirstVisibleItemPosition()
		assertEquals(initialAnchorIndex, initialVisiblePos)
		val initialView = manager.findViewByPosition(initialVisiblePos)
		assertNotNull(initialView)
		assertEquals(expectedOffset, initialView!!.top)
		assertTrue(rv.canScrollVertically(1))

		val anchorItem = adapter.currentList[initialVisiblePos]
		val anchorStableId = anchorItem.stableId
		assertEquals("\$event11", anchorItem.connectorMessageId)

		// 2. First pagination pass: load older messages (event5..event9) with overlapping events
		// (event10..event14, duplicate event10, and edited event11)
		fakeConnector.messages.clear()
		val page1 = mutableListOf<ConnectorMessage>()
		for (i in 5..9) {
			page1.add(connectorMessage("\$event$i", 1000 + i, "Message $i"))
		}
		for (i in 10..14) {
			if (i == 11) {
				page1.add(connectorMessage("\$event11", 1011, "Message 11 (updated)", isEdited = true))
			} else {
				page1.add(connectorMessage("\$event$i", 1000 + i, "Message $i"))
			}
		}
		// Duplicate overlapping event10 and event11
		page1.add(connectorMessage("\$event10", 1010, "Message 10"))
		page1.add(connectorMessage("\$event11", 1011, "Message 11"))
		for (i in 15..24) {
			page1.add(connectorMessage("\$event$i", 1000 + i, "Message $i"))
		}
		fakeConnector.messages.addAll(page1)
		sub.onUpdate()
		drainWork()
		awaitAdapterUpdate(adapter, 20)
		layoutRecyclerView(activity)

		val visiblePos1 = manager.findFirstVisibleItemPosition()
		val visibleItem1 = adapter.currentList[visiblePos1]
		assertEquals("\$event11", visibleItem1.connectorMessageId)
		assertEquals(anchorStableId, visibleItem1.stableId)
		assertEquals("Message 11 (updated)", visibleItem1.text)
		assertTrue(visibleItem1.isEdited)
		val visibleView1 = manager.findViewByPosition(visiblePos1)
		assertNotNull(visibleView1)
		assertEquals(expectedOffset, visibleView1!!.top)

		// 3. Second pagination pass: load even older messages (event1..event4) with overlapping (event5..event7)
		fakeConnector.messages.clear()
		val page2 = mutableListOf<ConnectorMessage>()
		for (i in 1..4) {
			page2.add(connectorMessage("\$event$i", 1000 + i, "Message $i"))
		}
		for (i in 5..7) {
			page2.add(connectorMessage("\$event$i", 1000 + i, "Message $i"))
		}
		page2.addAll(page1)
		fakeConnector.messages.addAll(page2)
		sub.onUpdate()
		drainWork()
		awaitAdapterUpdate(adapter, 24)
		layoutRecyclerView(activity)

		val visiblePos2 = manager.findFirstVisibleItemPosition()
		val visibleItem2 = adapter.currentList[visiblePos2]
		assertEquals("\$event11", visibleItem2.connectorMessageId)
		assertEquals(anchorStableId, visibleItem2.stableId)
		val visibleView2 = manager.findViewByPosition(visiblePos2)
		assertNotNull(visibleView2)
		assertEquals(expectedOffset, visibleView2!!.top)

		// 4. Remote arrivals: new messages arrive at the bottom
		fakeConnector.messages.addAll(
			listOf(
				connectorMessage("\$event25", 1025, "Message 25"),
				connectorMessage("\$event26", 1026, "Message 26"),
			),
		)
		sub.onUpdate()
		drainWork()
		awaitAdapterUpdate(adapter, 26)
		layoutRecyclerView(activity)

		val visiblePos3 = manager.findFirstVisibleItemPosition()
		val visibleItem3 = adapter.currentList[visiblePos3]
		assertEquals("\$event11", visibleItem3.connectorMessageId)
		assertEquals(anchorStableId, visibleItem3.stableId)
		val visibleView3 = manager.findViewByPosition(visiblePos3)
		assertNotNull(visibleView3)
		assertEquals(expectedOffset, visibleView3!!.top)
	}

	@Test
	fun onStopResetsInFlightDownloadsToIdleAndCancelsWorker() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val fileMessage = ConnectorMessage(
			source = ConnectorSources.MATRIX,
			threadId = "!room1:matrix.org",
			messageId = "\$file1",
			dateSeconds = 1000,
			isOutgoing = false,
			text = "attachment.pdf",
			sourceMessageOrder = 1000L,
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "attachment.pdf",
				mimeType = "application/pdf",
				size = 1024L,
				uri = "mxc://example.org/file1",
			),
		)
		fakeConnector.messages.add(fileMessage)
		fakeConnector.activeSubscription?.onUpdate?.invoke()
		drainWork()

		val items = messageState(activity).messages
		assertEquals(1, items.size)
		val item = items[0]

		activity.onFileClicked(item)
		assertEquals(
			ConnectorDownloadState.DOWNLOADING,
			messageState(activity).messages[0].downloadState,
		)

		val activeDownloadsField = ConnectorConversationActivity::class.java.getDeclaredField(
			"activeDownloads",
		).apply {
			isAccessible = true
		}
		val activeDownloads = activeDownloadsField.get(activity) as Map<*, *>
		assertEquals(1, activeDownloads.size)

		activity.resetActiveDownloads()

		assertEquals(
			ConnectorDownloadState.IDLE,
			messageState(activity).messages[0].downloadState,
		)
		assertTrue(activeDownloads.isEmpty())

		drainWork()
		assertEquals(
			ConnectorDownloadState.IDLE,
			messageState(activity).messages[0].downloadState,
		)
	}

	@Test
	fun missingSizeFileClickFailsImmediatelyWithoutStartingDownload() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val fileMessage = ConnectorMessage(
			source = ConnectorSources.MATRIX,
			threadId = "!room1:matrix.org",
			messageId = "\$file_null_size",
			dateSeconds = 1000,
			isOutgoing = false,
			text = "unknown.pdf",
			sourceMessageOrder = 1000L,
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "unknown.pdf",
				mimeType = "application/pdf",
				size = null,
				uri = "mxc://example.org/unknown",
			),
		)
		fakeConnector.messages.add(fileMessage)
		fakeConnector.activeSubscription?.onUpdate?.invoke()
		drainWork()

		val item = messageState(activity).messages[0]
		activity.onFileClicked(item)

		assertEquals(
			ConnectorDownloadState.FAILED,
			messageState(activity).messages[0].downloadState,
		)

		val activeDownloadsField = ConnectorConversationActivity::class.java.getDeclaredField(
			"activeDownloads",
		).apply {
			isAccessible = true
		}
		val activeDownloads = activeDownloadsField.get(activity) as Map<*, *>
		assertTrue(activeDownloads.isEmpty())
	}

	@Test
	fun activityTeardownDeletesOwnedDownloadsWithoutAffectingOtherScreens() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val otherDir = File(File(activity.cacheDir, "matrix_attachments"), "other_convo")
		otherDir.mkdirs()
		val otherFile = File(otherDir, "other.pdf")
		otherFile.writeText("other")
		assertTrue(otherFile.exists())

		val ownedDir = File(File(activity.cacheDir, "matrix_attachments"), "owned_convo")
		ownedDir.mkdirs()
		val ownedFile = File(ownedDir, "owned.pdf")
		ownedFile.writeText("owned")
		assertTrue(ownedFile.exists())
		activity.ownedDownloadDirs.add(ownedDir)

		destroy(activity)
		drainWork()

		assertFalse(ownedFile.exists())
		assertFalse(ownedDir.exists())
		assertTrue(otherFile.exists())
		assertTrue(otherDir.exists())
	}

	@Test
	fun fileClickCreatesDedicatedSubfolderAndCleansUpOnTeardown() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val fileMessage = ConnectorMessage(
			source = ConnectorSources.MATRIX,
			threadId = "!room1:matrix.org",
			messageId = "\$file_download_subfolder",
			dateSeconds = 1000,
			isOutgoing = false,
			text = "subfolder.pdf",
			sourceMessageOrder = 1000L,
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "subfolder.pdf",
				mimeType = "application/pdf",
				size = 500L,
				uri = "mxc://example.org/subfolder",
			),
		)
		fakeConnector.messages.add(fileMessage)
		fakeConnector.activeSubscription?.onUpdate?.invoke()
		drainWork()

		val item = messageState(activity).messages[0]
		activity.onFileClicked(item)
		drainWork()

		assertEquals(ConnectorDownloadState.DOWNLOADED, messageState(activity).messages[0].downloadState)
		val downloadedFile = messageState(activity).messages[0].downloadedFile
		assertNotNull(downloadedFile)
		assertTrue(downloadedFile!!.exists())
		val parentDir = downloadedFile.parentFile
		assertNotNull(parentDir)
		assertEquals(File(activity.cacheDir, "matrix_attachments"), parentDir!!.parentFile)

		destroy(activity)
		drainWork()
		assertFalse(downloadedFile.exists())
		assertFalse(parentDir.exists())
	}

	@Test
	fun onCreateSweepsOrphanedDownloadsFromLostInMemoryState() {
		val attachmentsRoot = File(RuntimeEnvironment.getApplication().cacheDir, "matrix_attachments")
		val orphanedSubdir = File(attachmentsRoot, "orphaned_${UUID.randomUUID()}")
		orphanedSubdir.mkdirs()
		val orphanedFile = File(orphanedSubdir, "stale.pdf")
		orphanedFile.writeText("stale data")
		assertTrue(orphanedFile.exists())

		val activity = createActivity()
		start(activity)
		drainWork()

		assertFalse(orphanedFile.exists())
		assertFalse(orphanedSubdir.exists())

		destroy(activity)
		drainWork()
	}

	@Test
	fun downloadedFileClickOpensFileSafelyWithContentUriAndReadGrant() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val fileMessage = ConnectorMessage(
			source = ConnectorSources.MATRIX,
			threadId = "!room1:matrix.org",
			messageId = "\$file_open_safe",
			dateSeconds = 1000,
			isOutgoing = false,
			text = "open_safe.pdf",
			sourceMessageOrder = 1000L,
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "open_safe.pdf",
				mimeType = "application/pdf",
				size = 350L,
				uri = "mxc://example.org/safe_pdf",
			),
		)
		fakeConnector.messages.add(fileMessage)
		fakeConnector.activeSubscription?.onUpdate?.invoke()
		drainWork()

		// First click downloads the file
		val item = messageState(activity).messages[0]
		activity.onFileClicked(item)
		drainWork()

		assertEquals(ConnectorDownloadState.DOWNLOADED, messageState(activity).messages[0].downloadState)
		val downloadedFile = messageState(activity).messages[0].downloadedFile
		assertNotNull(downloadedFile)
		assertTrue(downloadedFile!!.exists())

		// Register a compatible viewer in shadow PackageManager
		val shadowPackageManager = shadowOf(activity.packageManager)
		val resolveInfo = ResolveInfo().apply {
			activityInfo = ActivityInfo().apply {
				packageName = "org.example.pdfviewer"
				name = "org.example.pdfviewer.ViewerActivity"
			}
		}
		val authority = "${activity.packageName}.connector.fileprovider"
		val expectedContentUri = FileProvider.getUriForFile(activity, authority, downloadedFile)
		val filterIntent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(expectedContentUri, "application/pdf")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		shadowPackageManager.addResolveInfoForIntent(filterIntent, resolveInfo)

		// Second click on downloaded item directs open action
		val downloadedItem = messageState(activity).messages[0]
		activity.onFileClicked(downloadedItem)
		drainWork()

		val started = shadowOf(activity).nextStartedActivity
		assertNotNull(started)
		assertEquals(Intent.ACTION_CHOOSER, started.action)
		assertEquals(downloadedFile.name, started.getStringExtra(Intent.EXTRA_TITLE))

		val innerIntent = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
		assertNotNull(innerIntent)
		assertEquals(Intent.ACTION_VIEW, innerIntent!!.action)
		assertEquals(expectedContentUri, innerIntent.data)
		assertEquals("content", innerIntent.data?.scheme)
		assertEquals("application/pdf", innerIntent.type)
		assertTrue((innerIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
		assertEquals(0, innerIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

		destroy(activity)
		drainWork()
	}

	@Test
	fun absentViewerProducesRecoverableSnackbarWithoutActivityLaunch() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val fileMessage = ConnectorMessage(
			source = ConnectorSources.MATRIX,
			threadId = "!room1:matrix.org",
			messageId = "\$file_absent_viewer",
			dateSeconds = 1000,
			isOutgoing = false,
			text = "no_viewer.xyz",
			sourceMessageOrder = 1000L,
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "no_viewer.xyz",
				mimeType = "application/x-custom-unknown",
				size = 350L,
				uri = "mxc://example.org/unknown_type",
			),
		)
		fakeConnector.messages.add(fileMessage)
		fakeConnector.activeSubscription?.onUpdate?.invoke()
		drainWork()

		activity.onFileClicked(messageState(activity).messages[0])
		drainWork()
		assertEquals(ConnectorDownloadState.DOWNLOADED, messageState(activity).messages[0].downloadState)

		// Click downloaded file when no external viewer handles the intent
		activity.onFileClicked(messageState(activity).messages[0])
		drainWork()
		ShadowLooper.idleMainLooper()

		assertNull(shadowOf(activity).nextStartedActivity)
		val listField = ConnectorConversationActivity::class.java.getDeclaredField("list").apply {
			isAccessible =
				true
		}
		val list = listField.get(activity) as BriarRecyclerView
		val root = list.parent as FrameLayout
		val snackbarText = root.findViewById<TextView>(
			com.google.android.material.R.id.snackbar_text,
		)?.text?.toString()
		assertEquals(activity.getString(R.string.error_start_activity), snackbarText)
		assertEquals(ConnectorDownloadState.DOWNLOADED, messageState(activity).messages[0].downloadState)

		destroy(activity)
		drainWork()
	}

	@Test
	fun downloadedFileClickReDownloadsIfCachedFileWasDeleted() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val fileMessage = ConnectorMessage(
			source = ConnectorSources.MATRIX,
			threadId = "!room1:matrix.org",
			messageId = "\$file_redownload",
			dateSeconds = 1000,
			isOutgoing = false,
			text = "redownload.pdf",
			sourceMessageOrder = 1000L,
			type = ConnectorMessageType.FILE,
			attachment = ConnectorAttachmentMetadata(
				fileName = "redownload.pdf",
				mimeType = "application/pdf",
				size = 350L,
				uri = "mxc://example.org/redownload",
			),
		)
		fakeConnector.messages.add(fileMessage)
		fakeConnector.activeSubscription?.onUpdate?.invoke()
		drainWork()

		activity.onFileClicked(messageState(activity).messages[0])
		drainWork()

		assertEquals(ConnectorDownloadState.DOWNLOADED, messageState(activity).messages[0].downloadState)
		val file = messageState(activity).messages[0].downloadedFile!!
		assertTrue(file.exists())

		// Simulate external cache policy deletion
		file.delete()
		assertFalse(file.exists())

		// Clicking item re-initiates download
		activity.onFileClicked(messageState(activity).messages[0])
		assertEquals(ConnectorDownloadState.DOWNLOADING, messageState(activity).messages[0].downloadState)
		drainWork()

		assertEquals(ConnectorDownloadState.DOWNLOADED, messageState(activity).messages[0].downloadState)
		val newFile = messageState(activity).messages[0].downloadedFile
		assertNotNull(newFile)
		assertTrue(newFile!!.exists())

		destroy(activity)
		drainWork()
	}

	@Test
	fun executableFileRefusesToLaunchSafely() {
		val activity = createActivity()
		start(activity)
		drainWork()

		assertTrue(
			ConnectorConversationActivity.isExecutable("application/vnd.android.package-archive", "apk"),
		)
		assertTrue(ConnectorConversationActivity.isExecutable(null, "apk"))
		assertTrue(ConnectorConversationActivity.isExecutable(null, "dex"))
		assertTrue(ConnectorConversationActivity.isExecutable(null, "so"))
		assertTrue(ConnectorConversationActivity.isExecutable(null, "sh"))
		assertTrue(ConnectorConversationActivity.isExecutable("*/*", ""))
		assertFalse(ConnectorConversationActivity.isExecutable("application/pdf", "pdf"))
		assertFalse(ConnectorConversationActivity.isExecutable("image/png", "png"))

		val apkDir = File(File(activity.cacheDir, "matrix_attachments"), "apk_test")
		apkDir.mkdirs()
		val apkFile = File(apkDir, "malicious.apk")
		apkFile.writeText("fake apk binary")
		assertTrue(apkFile.exists())

		activity.openDownloadedFile(apkFile, "application/vnd.android.package-archive")
		drainWork()
		assertNull(shadowOf(activity).nextStartedActivity)

		val listField = ConnectorConversationActivity::class.java.getDeclaredField("list").apply {
			isAccessible =
				true
		}
		val list = listField.get(activity) as BriarRecyclerView
		val root = list.parent as FrameLayout
		val snackbarText = root.findViewById<TextView>(
			com.google.android.material.R.id.snackbar_text,
		)?.text?.toString()
		assertEquals(activity.getString(R.string.error_start_activity), snackbarText)

		// Extensionless APK file containing AndroidManifest.xml must be rejected even with null mimeType
		val apkNoExt = File(apkDir, "malicious_no_ext")
		apkNoExt.outputStream().use { fos ->
			ZipOutputStream(fos).use { zos ->
				zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
				zos.write("manifest".toByteArray())
				zos.closeEntry()
			}
		}
		assertTrue(apkNoExt.exists())
		assertTrue(ConnectorConversationActivity.isExecutable(apkNoExt, null, ""))
		activity.openDownloadedFile(apkNoExt, null)
		drainWork()
		assertNull(shadowOf(activity).nextStartedActivity)

		// Extensionless ELF binary must also be rejected
		val elfNoExt = File(apkDir, "malicious_elf")
		elfNoExt.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
		assertTrue(ConnectorConversationActivity.isExecutable(elfNoExt, null, ""))
		activity.openDownloadedFile(elfNoExt, null)
		drainWork()
		assertNull(shadowOf(activity).nextStartedActivity)

		// Extensionless script must also be rejected
		val scriptNoExt = File(apkDir, "malicious_script")
		scriptNoExt.writeText("#!/bin/sh\nexit 0")
		assertTrue(ConnectorConversationActivity.isExecutable(scriptNoExt, null, ""))
		activity.openDownloadedFile(scriptNoExt, null)
		drainWork()
		assertNull(shadowOf(activity).nextStartedActivity)

		// Extensionless zip with > 100 padding entries followed by AndroidManifest.xml must be rejected
		val zipWithPadding = File(apkDir, "padding_manifest_no_ext")
		zipWithPadding.outputStream().use { fos ->
			ZipOutputStream(fos).use { zos ->
				for (i in 0 until 150) {
					zos.putNextEntry(ZipEntry("padding_$i.txt"))
					zos.write("padding".toByteArray())
					zos.closeEntry()
				}
				zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
				zos.write("manifest".toByteArray())
				zos.closeEntry()
			}
		}
		assertTrue(ConnectorConversationActivity.isExecutable(zipWithPadding, null, ""))
		activity.openDownloadedFile(zipWithPadding, null)
		drainWork()
		assertNull(shadowOf(activity).nextStartedActivity)

		// Extensionless zip with > 100 padding entries followed by classes.dex must be rejected
		val zipWithDexPadding = File(apkDir, "padding_dex_no_ext")
		zipWithDexPadding.outputStream().use { fos ->
			ZipOutputStream(fos).use { zos ->
				for (i in 0 until 150) {
					zos.putNextEntry(ZipEntry("padding_$i.txt"))
					zos.write("padding".toByteArray())
					zos.closeEntry()
				}
				zos.putNextEntry(ZipEntry("classes.dex"))
				zos.write("dex".toByteArray())
				zos.closeEntry()
			}
		}
		assertTrue(ConnectorConversationActivity.isExecutable(zipWithDexPadding, null, ""))
		activity.openDownloadedFile(zipWithDexPadding, null)
		drainWork()
		assertNull(shadowOf(activity).nextStartedActivity)

		// Extensionless zip with > 100 padding safe entries must NOT be rejected as executable
		val safeZipManyEntries = File(apkDir, "safe_many_entries_no_ext")
		safeZipManyEntries.outputStream().use { fos ->
			ZipOutputStream(fos).use { zos ->
				for (i in 0 until 150) {
					zos.putNextEntry(ZipEntry("notes_$i.txt"))
					zos.write("hello".toByteArray())
					zos.closeEntry()
				}
			}
		}
		assertFalse(ConnectorConversationActivity.isExecutable(safeZipManyEntries, null, ""))

		// Non-executable zip file must NOT be detected as executable
		val safeZip = File(apkDir, "safe_archive.zip")
		safeZip.outputStream().use { fos ->
			ZipOutputStream(fos).use { zos ->
				zos.putNextEntry(ZipEntry("notes.txt"))
				zos.write("hello".toByteArray())
				zos.closeEntry()
			}
		}
		assertFalse(ConnectorConversationActivity.isExecutable(safeZip, "application/zip", "zip"))

		destroy(activity)
		drainWork()
	}

	@Test
	fun unknownFileWithoutExtensionFallsBackToOctetStreamNotWildcard() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val dir = File(File(activity.cacheDir, "matrix_attachments"), "octet_test")
		dir.mkdirs()
		val safeFile = File(dir, "unknown_data")
		safeFile.writeText("safe plain data")

		// Register viewer that only handles application/octet-stream
		val shadowPackageManager = shadowOf(activity.packageManager)
		val resolveInfo = ResolveInfo().apply {
			activityInfo = ActivityInfo().apply {
				packageName = "org.example.hexviewer"
				name = "org.example.hexviewer.ViewerActivity"
			}
		}
		val authority = "${activity.packageName}.connector.fileprovider"
		val expectedContentUri = FileProvider.getUriForFile(activity, authority, safeFile)
		val octetIntent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(expectedContentUri, "application/octet-stream")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		shadowPackageManager.addResolveInfoForIntent(octetIntent, resolveInfo)

		// Open with rawMime = null, should use application/octet-stream instead of */*
		activity.openDownloadedFile(safeFile, null)
		drainWork()

		val started = shadowOf(activity).nextStartedActivity
		assertNotNull("Chooser should start for application/octet-stream viewer", started)
		val innerIntent = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
		assertNotNull(innerIntent)
		assertEquals(Intent.ACTION_VIEW, innerIntent!!.action)
		assertEquals("application/octet-stream", innerIntent.type)
		assertEquals(expectedContentUri, innerIntent.data)
		assertTrue((innerIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

		destroy(activity)
		drainWork()
	}

	@Test
	fun fileProviderOnlyExposesMatrixAttachmentsDirectory() {
		val activity = createActivity()
		start(activity)
		drainWork()

		val authority = "${activity.packageName}.connector.fileprovider"
		val matrixAttachmentsDir = File(activity.cacheDir, "matrix_attachments")
		matrixAttachmentsDir.mkdirs()
		val validFile = File(matrixAttachmentsDir, "test.pdf")
		validFile.writeText("valid")
		val validUri = FileProvider.getUriForFile(activity, authority, validFile)
		assertNotNull(validUri)
		assertEquals("content", validUri.scheme)
		assertTrue(validUri.path?.contains("matrix_attachments") == true)

		// Files outside matrix_attachments (e.g. root cacheDir) must NOT be addressable
		val outsideFile = File(activity.cacheDir, "private_cache_file.txt")
		outsideFile.writeText("private")
		var rejected = false
		try {
			FileProvider.getUriForFile(activity, authority, outsideFile)
		} catch (e: IllegalArgumentException) {
			rejected = true
		}
		assertTrue("File outside matrix_attachments must be rejected by FileProvider", rejected)

		destroy(activity)
		drainWork()
	}

	private fun addReceivedImage(activity: ConnectorConversationActivity, messageId: String) {
		fakeConnector.messages.add(
			ConnectorMessage(
				source = ConnectorSources.MATRIX,
				threadId = "!room1:matrix.org",
				messageId = messageId,
				dateSeconds = 1000,
				isOutgoing = false,
				text = "photo.jpg",
				sourceMessageOrder = 1000L,
				type = ConnectorMessageType.PHOTO,
				attachment = ConnectorAttachmentMetadata(
					fileName = "photo.jpg",
					mimeType = "image/jpeg",
					size = 350L,
					uri = "mxc://example.org/photo",
				),
			),
		)
		fakeConnector.activeSubscription?.onUpdate?.invoke()
		drainWork()
		assertEquals(ConnectorMessageType.PHOTO, messageState(activity).messages[0].type)
	}

	@Test
	fun receivedImageTapDownloadsAndOpensInAppViewer() {
		val activity = createActivity()
		start(activity)
		drainWork()
		addReceivedImage(activity, "\$image_open")

		activity.onFileClicked(messageState(activity).messages[0])
		drainWork()

		val item = messageState(activity).messages[0]
		assertEquals(ConnectorDownloadState.DOWNLOADED, item.downloadState)
		val started = shadowOf(activity).nextStartedActivity
		assertEquals(
			ConnectorImageViewerActivity::class.java.name,
			started.component?.className,
		)
		assertEquals(
			item.downloadedFile!!.absolutePath,
			started.getStringExtra(ConnectorImageViewerActivity.EXTRA_IMAGE_PATH),
		)

		// Tapping again reopens the staged image in-app without an external chooser
		activity.onFileClicked(item)
		drainWork()
		assertEquals(
			ConnectorImageViewerActivity::class.java.name,
			shadowOf(activity).nextStartedActivity.component?.className,
		)

		destroy(activity)
		drainWork()
	}

	@Test
	fun failedImageFetchMarksFailedWithoutOpeningViewer() {
		val activity = createActivity()
		start(activity)
		drainWork()
		addReceivedImage(activity, "\$image_fail")
		fakeConnector.downloadFailure = ConnectorFileDownloader.FailureCause.NETWORK_ERROR

		activity.onFileClicked(messageState(activity).messages[0])
		drainWork()

		assertEquals(ConnectorDownloadState.FAILED, messageState(activity).messages[0].downloadState)
		assertNull(shadowOf(activity).nextStartedActivity)

		destroy(activity)
		drainWork()
	}
}
