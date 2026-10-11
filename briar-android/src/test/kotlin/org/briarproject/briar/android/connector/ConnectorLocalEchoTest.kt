package org.briarproject.briar.android.connector

import android.app.Application
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import java.util.concurrent.Executor
import org.briarproject.briar.R
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.android.view.BriarRecyclerView
import org.briarproject.briar.api.connector.ConnectorFileSender
import org.briarproject.briar.api.connector.ConnectorImageSender
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorTextSender
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorLocalEchoTest {
	private val server = ConnectorConversationMessageItem(
		ConnectorSources.MATRIX,
		"room",
		"event",
		2_000,
		true,
		"hello",
		ConnectorMessageType.TEXT,
	)

	private class MediaConnector :
		ReadOnlyConnector,
		ConnectorImageSender,
		ConnectorFileSender {
		override val source = ConnectorSources.MATRIX
		override fun isEnabled() = true
		override fun isAuthorized() = true
		override fun getRecentThreads(limit: Int) = emptyList<ConnectorThread>()
		override fun getRecentMessageReadResult(threadId: String, limit: Int) =
			ConnectorMessageReadResult.Success(emptyList())
		override fun sendImage(
			threadId: String,
			imageBytes: ByteArray,
			fileName: String,
			mimeType: String,
		) = true
		override fun prepareFileSend(threadId: String) =
			ConnectorFileSender.PreparedFileSend { _, _, _ -> true }
	}

	private fun buildActivityForMediaSend(
		connector: ReadOnlyConnector,
		work: MutableList<Runnable>,
	): ConnectorConversationActivity {
		Localizer.initialize(RuntimeEnvironment.getApplication().getSharedPreferences("test", 0))
		val activity = Robolectric.buildActivity(ConnectorConversationActivity::class.java).get()
		activity.setTheme(R.style.BriarTheme)
		val root = FrameLayout(activity).apply { id = android.R.id.content }
		val list = BriarRecyclerView(activity)
		root.addView(list)
		val manager = LinearLayoutManager(activity)
		list.setLayoutManager(manager)
		val adapter = ConnectorConversationActivity::class.java.getDeclaredField("adapter").apply {
			isAccessible = true
		}.get(activity) as ConnectorConversationAdapter
		list.setAdapter(adapter)
		setField(activity, "list", list)
		setField(activity, "layoutManager", manager)
		setField(activity, "composeText", EditText(activity))
		setField(activity, "composeBar", root)
		setField(activity, "sendButton", Button(activity))
		setField(activity, "attachButton", ImageButton(activity))
		setField(activity, "fileButton", ImageButton(activity))
		setField(activity, "connector", connector)
		setField(activity, "threadId", "room")
		activity.ioExecutor = Executor { work.add(it) }
		return activity
	}

	private fun setField(activity: ConnectorConversationActivity, name: String, value: Any?) {
		ConnectorConversationActivity::class.java.getDeclaredField(name).apply {
			isAccessible = true
		}.set(activity, value)
	}

	private fun getField(activity: ConnectorConversationActivity, name: String): Any? =
		ConnectorConversationActivity::class.java.getDeclaredField(name).apply {
			isAccessible = true
		}.get(activity)

	private fun invokeUriMethod(activity: ConnectorConversationActivity, method: String, uri: Uri?) {
		ConnectorConversationActivity::class.java.getDeclaredMethod(method, Uri::class.java).apply {
			isAccessible = true
		}.invoke(activity, uri)
	}

	private fun invokeCompleteEcho(
		activity: ConnectorConversationActivity,
		method: String,
		sent: Boolean,
	) {
		ConnectorConversationActivity::class.java
			.getDeclaredMethod(method, Boolean::class.javaPrimitiveType)
			.apply { isAccessible = true }
			.invoke(activity, sent)
	}

	private fun messageState(
		activity: ConnectorConversationActivity,
	): ConnectorConversationMessageListState = getField(activity, "messageState")
		as ConnectorConversationMessageListState

	@Test fun activityFailureRetainsComposeTextAndMarksPendingRowFailed() {
		Localizer.initialize(RuntimeEnvironment.getApplication().getSharedPreferences("test", 0))
		val activity = Robolectric.buildActivity(ConnectorConversationActivity::class.java).get()
		activity.setTheme(R.style.BriarTheme)
		val root = FrameLayout(activity).apply { id = android.R.id.content }
		val list = BriarRecyclerView(activity)
		root.addView(list)
		val compose = EditText(activity).apply { setText("  hello  ") }
		val connector = object : ReadOnlyConnector, ConnectorTextSender {
			override val source = ConnectorSources.MATRIX
			override fun isEnabled() = true
			override fun isAuthorized() = true
			override fun getRecentThreads(limit: Int) = emptyList<ConnectorThread>()
			override fun getRecentMessageReadResult(threadId: String, limit: Int) =
				ConnectorMessageReadResult.Success(emptyList())
			override fun sendText(threadId: String, text: String): Boolean {
				assertEquals("room", threadId)
				assertEquals("hello", text)
				return false
			}
		}
		fun set(name: String, value: Any) {
			ConnectorConversationActivity::class.java.getDeclaredField(name).apply {
				isAccessible = true
			}.set(activity, value)
		}
		val manager = LinearLayoutManager(activity)
		list.setLayoutManager(manager)
		val adapter = ConnectorConversationActivity::class.java.getDeclaredField("adapter").apply {
			isAccessible = true
		}.get(activity) as ConnectorConversationAdapter
		list.setAdapter(adapter)
		set("list", list)
		set("layoutManager", manager)
		set("composeText", compose)
		set("composeBar", root)
		set("sendButton", Button(activity))
		set("attachButton", ImageButton(activity))
		set("fileButton", ImageButton(activity))
		set("connector", connector)
		set("textSender", connector)
		set("threadId", "room")
		val work = mutableListOf<Runnable>()
		activity.ioExecutor = Executor { work.add(it) }
		ConnectorConversationActivity::class.java.getDeclaredMethod("onSendClicked").apply {
			isAccessible = true
		}.invoke(activity)
		fun state() = ConnectorConversationActivity::class.java.getDeclaredField("messageState")
			.apply { isAccessible = true }.get(activity) as ConnectorConversationMessageListState
		assertEquals(ConnectorLocalEchoStatus.PENDING, state().messages.single().localEchoStatus)
		assertEquals(false, compose.isEnabled)
		work.single().run()
		assertEquals(ConnectorLocalEchoStatus.FAILED, state().messages.single().localEchoStatus)
		assertEquals("  hello  ", compose.text.toString())
		assertTrue(compose.isEnabled)
	}

	@Test fun pendingRowAppendsAndReconcilesOnlyAfterSendCompletes() {
		val old = server.copy(connectorMessageId = "old")
		var state = ConnectorConversationMessageListState(messages = listOf(old))
			.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
		val echo = state.messages.last()
		assertEquals(old, state.messages.first())
		assertEquals(ConnectorLocalEchoStatus.PENDING, echo.localEchoStatus)
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		val holder = ConnectorConversationAdapter.MessageViewHolder(view)
		holder.bind(echo)
		assertEquals("hello", view.findViewById<TextView>(R.id.connectorMessageText).text.toString())
		assertEquals(
			"Sending…",
			view.findViewById<TextView>(R.id.connectorMessageDirection).text.toString(),
		)
		state = state.reconcile(listOf(old, server))
		assertEquals(3, state.messages.size)
		state = state.completeSend(echo.stableId, true).reconcile(listOf(old))
		val sentEcho = echo.copy(localEchoStatus = ConnectorLocalEchoStatus.SENT)
		assertEquals(sentEcho, state.messages.last())
		state = state.reconcile(listOf(old, server))
		assertEquals(listOf(old, server), state.messages)
		assertTrue(state.localEchoes.isEmpty())
		holder.bind(state.messages.last())
		assertEquals(
			view.resources.getString(R.string.connector_conversation_direction_outgoing),
			view.findViewById<TextView>(R.id.connectorMessageDirection).text.toString(),
		)
	}

	@Test fun failureKeepsTextAndIdentityThroughEmptyAndFailedReloads() {
		var state = ConnectorConversationMessageListState()
			.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
		val id = state.messages.single().stableId
		state = state.completeSend(id, false).reconcile(emptyList())
		state = ConnectorConversationMessageListState().withAvailabilityState(
			ConnectorConversationAvailabilityState.LOAD_FAILED,
			state,
			"load failed",
		)
		val failed = state.messages.single()
		assertEquals(id, failed.stableId)
		assertEquals("hello", failed.text)
		assertEquals(ConnectorLocalEchoStatus.FAILED, failed.localEchoStatus)
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		ConnectorConversationAdapter.MessageViewHolder(view).bind(failed)
		assertEquals(
			"Message could not be sent",
			view.findViewById<TextView>(R.id.connectorMessageDirection).text.toString(),
		)
		assertTrue(ConnectorConversationMessageListState().localEchoes.isEmpty())
	}

	@Test fun unrelatedRowsDoNotConsumeEcho() {
		var state = ConnectorConversationMessageListState()
			.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
		state = state.completeSend(state.messages.last().stableId, true)
		for (unrelated in listOf(
			server.copy(isOutgoing = false),
			server.copy(connectorThreadId = "other"),
			server.copy(connectorSource = ConnectorSources.TELEGRAM),
			server.copy(text = "other"),
		)) {
			assertEquals(1, state.reconcile(listOf(unrelated)).localEchoes.size)
		}
	}

	@Test fun successfulResendDoesNotConsumeTheEarlierFailedRow() {
		var state = ConnectorConversationMessageListState()
			.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
		val failedId = state.messages.last().stableId
		state = state.completeSend(failedId, false)
			.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
		state = state.completeSend(state.messages.last().stableId, true)
			.reconcile(listOf(server))
		assertEquals(listOf(server), state.messages.filter { it.localEchoStatus == null })
		assertEquals(failedId, state.localEchoes.single().item.stableId)
		assertEquals(ConnectorLocalEchoStatus.FAILED, state.messages.last().localEchoStatus)
	}

	@Test fun oneServerMessageCannotConsumeTwoIdenticalSendsAcrossReloads() {
		var state = ConnectorConversationMessageListState()
		repeat(2) {
			state = state.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
			state = state.completeSend(state.messages.last().stableId, true)
		}
		state = state.reconcile(listOf(server)).reconcile(listOf(server))
		assertEquals(1, state.localEchoes.size)
		assertEquals(2, state.messages.size)
		state = state.reconcile(listOf(server, server.copy(connectorMessageId = "second")))
		assertTrue(state.localEchoes.isEmpty())
	}

	@Test fun imageSendAddsPendingPhotoRowThenReconcilesOnSuccess() {
		val connector = MediaConnector()
		val work = mutableListOf<Runnable>()
		val activity = buildActivityForMediaSend(connector, work)
		setField(activity, "imageSender", connector)
		setField(activity, "imageUpload", ConnectorImageUploadState())

		invokeUriMethod(activity, "onImageChosen", Uri.parse("content://picker/image/1"))

		val pending = messageState(activity).messages.last()
		assertEquals(ConnectorMessageType.PHOTO, pending.type)
		assertEquals(ConnectorLocalEchoStatus.PENDING, pending.localEchoStatus)
		assertEquals(pending.stableId, getField(activity, "pendingImageEchoId"))
		assertTrue("send should be queued, not yet run", work.isNotEmpty())
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		ConnectorConversationAdapter.MessageViewHolder(view).bind(pending)
		assertEquals(
			view.resources.getString(R.string.connector_message_photo),
			view.findViewById<TextView>(R.id.connectorMessageText).text.toString(),
		)

		invokeCompleteEcho(activity, "completeImageEcho", true)

		assertNull(getField(activity, "pendingImageEchoId"))
		val server = pending.copy(connectorMessageId = "server", localEchoStatus = null)
		assertEquals(listOf(server), messageState(activity).reconcile(listOf(server)).messages)
	}

	@Test fun imageSendFailureMarksPendingRowFailedAndKeepsRecoverableSelection() {
		val connector = MediaConnector()
		val work = mutableListOf<Runnable>()
		val activity = buildActivityForMediaSend(connector, work)
		setField(activity, "imageSender", connector)
		val imageUpload = ConnectorImageUploadState()
		setField(activity, "imageUpload", imageUpload)
		val uri = Uri.parse("content://picker/image/2")

		invokeUriMethod(activity, "onImageChosen", uri)
		imageUpload.complete(false)
		invokeCompleteEcho(activity, "completeImageEcho", false)

		assertEquals(
			ConnectorLocalEchoStatus.FAILED,
			messageState(activity).messages.last().localEchoStatus,
		)
		assertEquals(uri.toString(), imageUpload.pendingImage)
	}

	@Test fun fileSendAddsPendingFileRowThenReconcilesOnSuccess() {
		val connector = MediaConnector()
		val work = mutableListOf<Runnable>()
		val activity = buildActivityForMediaSend(connector, work)
		setField(activity, "fileSender", connector)
		val fileUpload = ConnectorFileUploadState()
		fileUpload.pick(connector.prepareFileSend("room")!!)
		setField(activity, "fileUpload", fileUpload)

		invokeUriMethod(activity, "onFileChosen", Uri.parse("content://provider/document/1"))

		val pending = messageState(activity).messages.last()
		assertEquals(ConnectorMessageType.FILE, pending.type)
		assertEquals(ConnectorLocalEchoStatus.PENDING, pending.localEchoStatus)
		assertEquals(pending.stableId, getField(activity, "pendingFileEchoId"))
		assertTrue("send should be queued, not yet run", work.isNotEmpty())
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		ConnectorConversationAdapter.MessageViewHolder(view).bind(pending)
		assertEquals(
			view.resources.getString(R.string.connector_message_file),
			view.findViewById<TextView>(R.id.connectorMessageText).text.toString(),
		)

		invokeCompleteEcho(activity, "completeFileEcho", true)

		assertNull(getField(activity, "pendingFileEchoId"))
		val server = pending.copy(connectorMessageId = "server", localEchoStatus = null)
		assertEquals(listOf(server), messageState(activity).reconcile(listOf(server)).messages)
	}

	@Test fun fileSendFailureMarksPendingRowFailedInPlace() {
		val connector = MediaConnector()
		val work = mutableListOf<Runnable>()
		val activity = buildActivityForMediaSend(connector, work)
		setField(activity, "fileSender", connector)
		val fileUpload = ConnectorFileUploadState()
		fileUpload.pick(connector.prepareFileSend("room")!!)
		setField(activity, "fileUpload", fileUpload)

		invokeUriMethod(activity, "onFileChosen", Uri.parse("content://provider/document/2"))
		invokeCompleteEcho(activity, "completeFileEcho", false)

		val failed = messageState(activity).messages.last()
		assertEquals(ConnectorMessageType.FILE, failed.type)
		assertEquals(ConnectorLocalEchoStatus.FAILED, failed.localEchoStatus)
	}

	@Test fun completingMediaEchoWithoutAPendingSendIsANoOpAcrossActivityRecreation() {
		val connector = MediaConnector()
		val work = mutableListOf<Runnable>()
		val activity = buildActivityForMediaSend(connector, work)
		setField(activity, "imageSender", connector)
		setField(activity, "fileSender", connector)

		invokeCompleteEcho(activity, "completeImageEcho", true)
		invokeCompleteEcho(activity, "completeFileEcho", false)

		assertTrue(messageState(activity).messages.isEmpty())
	}
}
