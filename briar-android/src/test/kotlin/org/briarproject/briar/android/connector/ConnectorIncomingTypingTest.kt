package org.briarproject.briar.android.connector

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.recyclerview.widget.LinearLayoutManager
import org.briarproject.briar.R
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.android.view.BriarRecyclerView
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorTextSender
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ConnectorTypingObserver
import org.briarproject.briar.api.connector.ConnectorTypingObserver.TypingSubscription
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConnectorIncomingTypingTest {

	@get:Rule val rule = InstantTaskExecutorRule()

	private class FakeTypingConnector :
		ReadOnlyConnector,
		ConnectorTextSender,
		ConnectorTypingObserver {
		override val source = ConnectorSources.MATRIX
		override fun isEnabled() = true
		override fun isAuthorized() = true
		override fun getRecentThreads(limit: Int) = emptyList<ConnectorThread>()

		override fun getRecentMessageReadResult(threadId: String, limit: Int) =
			ConnectorMessageReadResult.Success(emptyList())

		override fun sendText(threadId: String, text: String) = true

		var activeSubscription: FakeSubscription? = null

		override fun observeTyping(
			threadId: String,
			onTypingChanged: (Boolean) -> Unit,
		): TypingSubscription {
			val sub = FakeSubscription(onTypingChanged)
			activeSubscription = sub
			return sub
		}

		class FakeSubscription(val onTypingChanged: (Boolean) -> Unit) : TypingSubscription {
			var cancelled = false
			override fun cancel() {
				cancelled = true
			}
			override fun close() = cancel()
		}
	}

	private val fakeConnector = FakeTypingConnector()
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
		val typingIndicator = TextView(activity).apply {
			visibility = View.GONE
			text = activity.getString(R.string.connector_typing_indicator)
		}
		setField(activity, "list", list)
		setField(activity, "layoutManager", manager)
		setField(activity, "composeText", composeText)
		setField(activity, "composeBar", root)
		setField(activity, "typingIndicator", typingIndicator)
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

	@Test
	fun peerStartsAndStopsTypingControlsIndicatorVisibility() {
		val activity = createActivity()
		activity.startTypingObserver()
		drainWork()

		val sub = fakeConnector.activeSubscription
		assertNotNull(sub)
		assertFalse(sub!!.cancelled)

		val indicator = activity.getTypingIndicator()
		assertEquals(View.GONE, indicator.visibility)

		// Peer starts typing -> indicator becomes visible
		sub.onTypingChanged(true)
		drainWork()
		assertEquals(View.VISIBLE, indicator.visibility)
		assertEquals("Typing…", indicator.text.toString())

		// Peer stops typing -> indicator becomes gone
		sub.onTypingChanged(false)
		drainWork()
		assertEquals(View.GONE, indicator.visibility)
	}

	@Test
	fun typingIndicatorExpiresAfterTimeout() {
		val activity = createActivity()
		activity.startTypingObserver()
		drainWork()

		val sub = fakeConnector.activeSubscription!!
		val indicator = activity.getTypingIndicator()

		// Peer starts typing
		sub.onTypingChanged(true)
		drainWork()
		assertEquals(View.VISIBLE, indicator.visibility)

		// Before expiry (4000ms), indicator is still visible
		ShadowLooper.idleMainLooper(4000, TimeUnit.MILLISECONDS)
		assertEquals(View.VISIBLE, indicator.visibility)

		// After reaching expiry timeout (5000ms), indicator clears
		ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)
		assertEquals(View.GONE, indicator.visibility)
	}

	@Test
	fun leavingScreenCancelsObserverAndClearsIndicator() {
		val activity = createActivity()
		activity.startTypingObserver()
		drainWork()

		val sub = fakeConnector.activeSubscription!!
		val indicator = activity.getTypingIndicator()

		// Peer starts typing
		sub.onTypingChanged(true)
		drainWork()
		assertEquals(View.VISIBLE, indicator.visibility)

		// Leaving screen (stopTypingObserver) cancels subscription and clears indicator immediately
		activity.stopTypingObserver()
		assertTrue(sub.cancelled)
		assertEquals(View.GONE, indicator.visibility)

		// Further updates after leaving screen do not show indicator
		sub.onTypingChanged(true)
		drainWork()
		assertEquals(View.GONE, indicator.visibility)
	}
}
