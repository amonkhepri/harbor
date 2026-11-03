package org.briarproject.briar.android.connector

import android.app.Application
import android.content.Intent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.recyclerview.widget.LinearLayoutManager
import org.briarproject.briar.R
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.android.view.BriarRecyclerView
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorConversationOfflineRetryTest {

	@get:Rule val rule = InstantTaskExecutorRule()

	private class FakeConnector :
		ReadOnlyConnector,
		ConnectorTextSender,
		ConnectorRoomObserver {
		override val source = ConnectorSources.MATRIX
		override fun isEnabled() = true
		override fun isAuthorized() = true
		override fun getRecentThreads(limit: Int) = emptyList<ConnectorThread>()

		val messages = mutableListOf<ConnectorMessage>()
		var failLoads = false
		var readCount = 0

		override fun getRecentMessageReadResult(
			threadId: String,
			limit: Int,
		): ConnectorMessageReadResult {
			readCount++
			return if (failLoads) {
				ConnectorMessageReadResult.LoadFailed
			} else {
				ConnectorMessageReadResult.Success(messages.toList())
			}
		}

		override fun sendText(threadId: String, text: String) = true

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
		root.addView(list)
		val manager = LinearLayoutManager(activity)
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

	private fun loadInitial(activity: ConnectorConversationActivity) {
		val method = ConnectorConversationActivity::class.java.getDeclaredMethod(
			"loadMessages",
			Boolean::class.javaPrimitiveType,
			Boolean::class.javaPrimitiveType,
		)
		method.isAccessible = true
		method.invoke(activity, false, true)
	}

	private fun messageState(
		activity: ConnectorConversationActivity,
	): ConnectorConversationMessageListState =
		ConnectorConversationActivity::class.java.getDeclaredField("messageState").apply {
			isAccessible = true
		}.get(activity) as ConnectorConversationMessageListState

	private fun createMessage(id: String, text: String, seconds: Long): ConnectorMessage =
		ConnectorMessage(
			source = ConnectorSources.MATRIX,
			threadId = "!room1:matrix.org",
			messageId = id,
			dateSeconds = seconds.toInt(),
			isOutgoing = false,
			text = text,
			sourceMessageOrder = seconds,
			type = ConnectorMessageType.TEXT,
		)

	@Test
	fun disconnectPreservesVisibleHistory() {
		fakeConnector.messages.add(createMessage("\$event1", "Message 1", 1000L))
		fakeConnector.messages.add(createMessage("\$event2", "Message 2", 1001L))
		val activity = createActivity()
		activity.startRoomObserver()
		loadInitial(activity)
		drainWork()

		// Initial load successful
		val stateBefore = messageState(activity)
		assertEquals(2, stateBefore.messages.size)

		// Disconnect / network loss occurs
		fakeConnector.failLoads = true

		// Room refresh is triggered while offline (e.g. observer or manual/periodic refresh)
		activity.retryRefresh()
		drainWork()

		// History MUST stay visible despite load failure
		val stateAfter = messageState(activity)
		assertEquals(2, stateAfter.messages.size)
		assertEquals("Message 1", stateAfter.messages[0].text)
		assertEquals("Message 2", stateAfter.messages[1].text)
	}

	@Test
	fun retryRecoversAfterReconnect() {
		fakeConnector.messages.add(createMessage("\$event1", "Message 1", 1000L))
		val activity = createActivity()
		activity.startRoomObserver()
		loadInitial(activity)
		drainWork()

		assertEquals(1, messageState(activity).messages.size)

		// Disconnect
		fakeConnector.failLoads = true
		activity.retryRefresh()
		drainWork()

		// History is preserved
		assertEquals(1, messageState(activity).messages.size)

		// Reconnect with new remote message
		fakeConnector.failLoads = false
		fakeConnector.messages.add(createMessage("\$event2", "Message 2", 1001L))

		// Explicit retry recovers fresh history
		activity.retryRefresh()
		drainWork()

		val recoveredState = messageState(activity)
		assertEquals(2, recoveredState.messages.size)
		assertEquals("Message 1", recoveredState.messages[0].text)
		assertEquals("Message 2", recoveredState.messages[1].text)
	}

	@Test
	fun duplicateRetriesCoalesce() {
		fakeConnector.messages.add(createMessage("\$event1", "Message 1", 1000L))
		val activity = createActivity()
		activity.startRoomObserver()
		loadInitial(activity)
		drainWork()

		fakeConnector.readCount = 0

		// Fire multiple rapid retries before draining work (simulating rapid clicks while in flight)
		repeat(5) {
			activity.retryRefresh()
		}

		// Drain work
		drainWork()

		// At most one in-flight plus one pending follow-up executed, NOT 5
		assertTrue(fakeConnector.readCount in 1..2)
		assertEquals(1, messageState(activity).messages.size)
	}

	@Test
	fun failedInitialLoadShowsEmptyStateAndRetryRecovers() {
		fakeConnector.failLoads = true
		val activity = createActivity()
		activity.startRoomObserver()
		loadInitial(activity)
		drainWork()

		// Initial load failed on empty room
		assertEquals(0, messageState(activity).messages.size)

		// Reconnect and add message
		fakeConnector.failLoads = false
		fakeConnector.messages.add(createMessage("\$event1", "First message", 1000L))

		// Retry recovers
		activity.retryRefresh()
		drainWork()

		assertEquals(1, messageState(activity).messages.size)
		assertEquals("First message", messageState(activity).messages.single().text)
	}
}
