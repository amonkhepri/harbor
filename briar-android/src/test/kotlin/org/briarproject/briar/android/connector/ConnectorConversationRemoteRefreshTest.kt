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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ConnectorConversationRemoteRefreshTest {

	@get:Rule val rule = InstantTaskExecutorRule()

	private class FakeConnector : ReadOnlyConnector, ConnectorTextSender, ConnectorRoomObserver {
		override val source = ConnectorSources.MATRIX
		override fun isEnabled() = true
		override fun isAuthorized() = true
		override fun getRecentThreads(limit: Int) = emptyList<ConnectorThread>()

		val messages = mutableListOf<ConnectorMessage>()

		override fun getRecentMessageReadResult(threadId: String, limit: Int) =
			ConnectorMessageReadResult.Success(messages.toList())

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

	private fun start(activity: ConnectorConversationActivity) {
		activity.startRoomObserver()
	}

	private fun destroy(activity: ConnectorConversationActivity) {
		activity.stopRoomObserver()
	}

	private fun messageState(
		activity: ConnectorConversationActivity,
	): ConnectorConversationMessageListState =
		ConnectorConversationActivity::class.java.getDeclaredField("messageState").apply {
			isAccessible = true
		}.get(activity) as ConnectorConversationMessageListState

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
}
