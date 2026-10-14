package org.briarproject.briar.android.connector

import android.app.Application
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import java.util.concurrent.Executor
import org.briarproject.briar.R
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.android.view.BriarRecyclerView
import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReadMarker
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorReadMarkerTest {

	private open class FakeConnector : ReadOnlyConnector {
		override val source get() = ConnectorSources.TELEGRAM
		var authorized = true
		var reads = 0
		var result: ConnectorMessageReadResult = ConnectorMessageReadResult.Success(
			listOf(
				ConnectorMessage(
					source,
					"room",
					"event",
					1,
					false,
					"hello",
					1,
					ConnectorMessageType.TEXT,
				),
			),
		)
		override fun isEnabled() = true
		override fun isAuthorized() = authorized
		override fun getRecentThreads(limit: Int) = emptyList<ConnectorThread>()
		override fun getRecentMessageReadResult(
			threadId: String,
			limit: Int,
		): ConnectorMessageReadResult {
			reads++
			return result
		}
	}

	private class MarkingConnector :
		FakeConnector(),
		ConnectorReadMarker {
		override val source get() = ConnectorSources.MATRIX
		var marks = 0
		override fun markThreadRead(threadId: String): Boolean {
			assertEquals("room", threadId)
			marks++
			return true
		}
	}

	private val work = mutableListOf<Runnable>()

	private fun activity(connector: ReadOnlyConnector): ConnectorConversationActivity {
		Localizer.initialize(RuntimeEnvironment.getApplication().getSharedPreferences("test", 0))
		val activity = Robolectric.buildActivity(ConnectorConversationActivity::class.java).get()
		activity.setTheme(R.style.BriarTheme)
		fun set(name: String, value: Any?) {
			ConnectorConversationActivity::class.java.getDeclaredField(name).apply {
				isAccessible = true
			}.set(activity, value)
		}
		val root = FrameLayout(activity).apply { id = android.R.id.content }
		val list = BriarRecyclerView(activity)
		root.addView(list)
		val manager = LinearLayoutManager(activity)
		list.setLayoutManager(manager)
		val adapter = ConnectorConversationActivity::class.java.getDeclaredField("adapter")
			.apply { isAccessible = true }.get(activity) as ConnectorConversationAdapter
		list.setAdapter(adapter)
		set("list", list)
		set("layoutManager", manager)
		set("composeBar", root)
		set("attachButton", ImageButton(activity))
		set("fileButton", ImageButton(activity))
		set("findBar", FrameLayout(activity))
		set("findQuery", EditText(activity))
		set("findCount", TextView(activity))
		set("findPrevious", ImageButton(activity))
		set("findNext", ImageButton(activity))
		set("connector", connector)
		set("readMarker", connector as? ConnectorReadMarker)
		set("sourceName", connector.source.displayName)
		set("threadId", "room")
		activity.ioExecutor = Executor { work.add(it) }
		return activity
	}

	private fun load(activity: ConnectorConversationActivity, initial: Boolean = false) {
		ConnectorConversationActivity::class.java.getDeclaredMethod(
			"loadMessages",
			Boolean::class.javaPrimitiveType,
			Boolean::class.javaPrimitiveType,
		).apply { isAccessible = true }.invoke(activity, false, initial)
	}

	@Test fun openingAndNonemptyRefreshEachMarkOnceOnExecutor() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		load(activity, initial = true)
		assertEquals(0, connector.marks)
		work.removeAt(0).run()
		assertEquals(1, connector.marks)
		load(activity)
		work.removeAt(0).run()
		assertEquals(2, connector.marks)
		connector.result = ConnectorMessageReadResult.Success(emptyList())
		load(activity)
		work.removeAt(0).run()
		assertEquals(2, connector.marks)
	}

	@Test fun unauthorizedAndFailedLoadsNeverMark() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		connector.authorized = false
		load(activity, initial = true)
		work.removeAt(0).run()
		assertEquals(0, connector.reads)
		connector.authorized = true
		connector.result = ConnectorMessageReadResult.LoadFailed
		load(activity, initial = true)
		work.removeAt(0).run()
		assertEquals(1, connector.reads)
		assertEquals(0, connector.marks)
	}

	@Test fun supersededLoadCannotMark() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		load(activity, initial = true)
		load(activity)
		work.removeAt(0).run()
		assertEquals(0, connector.marks)
		work.removeAt(0).run()
		assertEquals(1, connector.marks)
	}

	@Test fun telegramWithoutCapabilityStillLoads() {
		val connector = FakeConnector()
		val activity = activity(connector)
		load(activity, initial = true)
		work.removeAt(0).run()
		load(activity)
		work.removeAt(0).run()
		assertEquals(2, connector.reads)
	}
}
