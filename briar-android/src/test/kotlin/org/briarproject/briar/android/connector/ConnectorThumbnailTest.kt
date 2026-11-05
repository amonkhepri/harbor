package org.briarproject.briar.android.connector

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.briarproject.briar.R
import org.briarproject.briar.api.connector.ConnectorAttachmentMetadata
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThumbnailLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConnectorThumbnailTest {

	private val directExecutor = Executor { it.run() }
	private val directHandler = Handler(Looper.getMainLooper())

	private fun createSamplePng(width: Int = 100, height: Int = 100): ByteArray {
		val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val stream = ByteArrayOutputStream()
		bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
		return stream.toByteArray()
	}

	private fun photoItem(
		messageId: String = "m1",
		threadId: String = "t1",
		mediaUrl: String? = "mxc://example.org/photo1",
	) = ConnectorConversationMessageItem(
		connectorSource = ConnectorSources.MATRIX,
		connectorThreadId = threadId,
		connectorMessageId = messageId,
		dateMillis = 1_000L,
		isOutgoing = false,
		text = "Photo",
		type = ConnectorMessageType.PHOTO,
		attachment = if (mediaUrl != null) ConnectorAttachmentMetadata(uri = mediaUrl) else null,
	)

	@Test
	fun decodeBoundedBitmapReturnsDecodedImageForValidBytes() {
		val bytes = createSamplePng(120, 80)
		val decoded = ConnectorThumbnailManager.decodeBoundedBitmap(bytes, 200, 200)

		assertNotNull(decoded)
		assertEquals(120, decoded!!.width)
		assertEquals(80, decoded.height)
	}

	@Test
	fun decodeBoundedBitmapDownsamplesLargeImage() {
		val bytes = createSamplePng(800, 800)
		val decoded = ConnectorThumbnailManager.decodeBoundedBitmap(bytes, 200, 200)

		assertNotNull(decoded)
		assertTrue(decoded!!.width <= 400)
		assertTrue(decoded.height <= 400)
	}

	@Test
	fun decodeBoundedBitmapReturnsNullForCorruptBytesWithoutCrashing() {
		val corrupt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
		val decoded = ConnectorThumbnailManager.decodeBoundedBitmap(corrupt, 200, 200)

		assertNull(decoded)
	}

	@Test
	fun decodeBoundedBitmapReturnsNullForEmptyBytes() {
		val decoded = ConnectorThumbnailManager.decodeBoundedBitmap(ByteArray(0), 200, 200)

		assertNull(decoded)
	}

	@Test
	fun decodeBoundedBitmapReturnsNullForOversizedBytesWithoutCrashing() {
		val oversized = ByteArray(ConnectorThumbnailManager.MAX_IMAGE_BYTES + 1)
		val decoded = ConnectorThumbnailManager.decodeBoundedBitmap(oversized, 200, 200)

		assertNull(decoded)
	}

	@Test
	fun thumbnailManagerCachesLoadedBitmap() {
		val bytes = createSamplePng(100, 100)
		var loadCalls = 0
		val loader = ConnectorThumbnailLoader { _, _, _, _, _ ->
			loadCalls++
			bytes
		}
		val manager = ConnectorThumbnailManager(loader, directExecutor, directHandler)
		val item = photoItem("msg1", "thread1")

		var loadedBitmap: Bitmap? = null
		manager.loadThumbnail(item) { loadedBitmap = it }

		assertNotNull(loadedBitmap)
		assertEquals(1, loadCalls)
		assertNotNull(manager.getCached("thread1", "msg1"))

		var secondBitmap: Bitmap? = null
		manager.loadThumbnail(item) { secondBitmap = it }
		assertNotNull(secondBitmap)
		assertEquals(1, loadCalls)
	}

	@Test
	fun thumbnailManagerClearEvictsCache() {
		val bytes = createSamplePng(100, 100)
		val loader = ConnectorThumbnailLoader { _, _, _, _, _ -> bytes }
		val manager = ConnectorThumbnailManager(loader, directExecutor, directHandler)
		val item = photoItem("msg1", "thread1")

		manager.loadThumbnail(item) {}
		assertNotNull(manager.getCached("thread1", "msg1"))

		manager.clear()
		assertNull(manager.getCached("thread1", "msg1"))
	}

	@Test
	fun adapterBindsPhotoThumbnailAndRecyclesSafely() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val view = LayoutInflater.from(context).inflate(R.layout.list_item_connector_message, null)
		val bytes = createSamplePng(100, 100)
		val loader = ConnectorThumbnailLoader { _, _, _, _, _ -> bytes }
		val manager = ConnectorThumbnailManager(loader, directExecutor, directHandler)
		val holder = ConnectorConversationAdapter.MessageViewHolder(
			itemView = view,
			onDeleteClicked = null,
			onRetrySendClicked = null,
			onFileClicked = null,
			thumbnailManager = manager,
		)
		val thumbnail = view.findViewById<ImageView>(R.id.connectorMessageThumbnail)

		val item = photoItem("msg1", "thread1")
		holder.bind(item)

		assertEquals(View.VISIBLE, thumbnail.visibility)
		assertNotNull(thumbnail.drawable)

		// Recycling clears the view and hides it
		holder.recycle()
		assertEquals(View.GONE, thumbnail.visibility)
		assertNull(thumbnail.drawable)
	}

	@Test
	fun adapterDoesNotDisplayPriorRoomContentWhenRowIsRecycledBeforeLoadCompletes() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val view = LayoutInflater.from(context).inflate(R.layout.list_item_connector_message, null)
		var callbackToRun: Runnable? = null
		val pausedExecutor = Executor { runnable -> callbackToRun = runnable }
		val bytes = createSamplePng(100, 100)
		val loader = ConnectorThumbnailLoader { _, _, _, _, _ -> bytes }
		val manager = ConnectorThumbnailManager(loader, pausedExecutor, directHandler)
		val holder = ConnectorConversationAdapter.MessageViewHolder(
			itemView = view,
			onDeleteClicked = null,
			onRetrySendClicked = null,
			onFileClicked = null,
			thumbnailManager = manager,
		)
		val thumbnail = view.findViewById<ImageView>(R.id.connectorMessageThumbnail)

		val itemRoomA = photoItem("msgA", "roomA")
		holder.bind(itemRoomA)
		assertNull(thumbnail.drawable)
		val taskA = callbackToRun

		// Recycled and rebound to a different room message before itemRoomA finishes
		holder.recycle()
		val itemRoomB = photoItem("msgB", "roomB")
		holder.bind(itemRoomB)
		val taskB = callbackToRun

		// Late delivery from room A
		taskA?.run()

		// Thumbnail for room B was not yet loaded; late room A load must not leak into room B
		assertNull(thumbnail.drawable)

		// When room B finishes, it loads room B's thumbnail
		taskB?.run()
		assertNotNull(thumbnail.drawable)
	}

	@Test
	fun adapterDisplaysBrokenIconWhenThumbnailFailsToDecode() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val view = LayoutInflater.from(context).inflate(R.layout.list_item_connector_message, null)
		val loader = ConnectorThumbnailLoader { _, _, _, _, _ -> byteArrayOf(1, 2, 3) }
		val manager = ConnectorThumbnailManager(loader, directExecutor, directHandler)
		val holder = ConnectorConversationAdapter.MessageViewHolder(
			itemView = view,
			onDeleteClicked = null,
			onRetrySendClicked = null,
			onFileClicked = null,
			thumbnailManager = manager,
		)
		val thumbnail = view.findViewById<ImageView>(R.id.connectorMessageThumbnail)

		val item = photoItem("msg1", "thread1")
		holder.bind(item)

		assertEquals(View.VISIBLE, thumbnail.visibility)
		assertNotNull(thumbnail.drawable)
	}
}
