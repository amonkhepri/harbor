package org.briarproject.briar.android.connector

import android.app.Application
import androidx.appcompat.app.AlertDialog
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
import org.briarproject.briar.api.connector.ConnectorRoomLeaver
import org.briarproject.briar.api.connector.ConnectorUnreadMarker
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
		var enabled = true
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
		override fun isEnabled() = enabled
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
		ConnectorReadMarker,
		ConnectorRoomLeaver,
		ConnectorUnreadMarker {
		var leaves = 0
		var leaveResult = true
		var beforeLeave: () -> Unit = {}
		override fun prepareLeave(threadId: String) = object : ConnectorRoomLeaver.PreparedLeave {
			override fun isCurrent() = authorized
			override fun leave(): Boolean {
				leaves++
				beforeLeave()
				return leaveResult
			}
		}
		override val source get() = ConnectorSources.MATRIX
		var marks = 0
		var unreadMarks = 0
		var unreadResult = true
		var beforeRead: () -> Unit = {}
		var beforeUnread: () -> Unit = {}
		override fun prepareMarkUnread(threadId: String) =
			object : ConnectorUnreadMarker.PreparedMarkUnread {
				override fun isCurrent() = authorized
				override fun markUnread(): Boolean {
					beforeUnread()
					unreadMarks++
					return unreadResult
				}
			}
		override fun markThreadRead(threadId: String): Boolean {
			beforeRead()
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

	private fun markUnread(activity: ConnectorConversationActivity) {
		ConnectorConversationActivity::class.java.getDeclaredMethod("markUnreadAndClose")
			.apply { isAccessible = true }.invoke(activity)
	}

	private fun leaveDialog(activity: ConnectorConversationActivity): AlertDialog {
		ConnectorConversationActivity::class.java.getDeclaredMethod("confirmLeaveRoom")
			.apply { isAccessible = true }.invoke(activity)
		return field(activity, "leaveDialog") as AlertDialog
	}

	private fun field(activity: ConnectorConversationActivity, name: String): Any? =
		ConnectorConversationActivity::class.java.getDeclaredField(name)
			.apply { isAccessible = true }.get(activity)

	@Test fun leaveRequiresConfirmationAndSuppressesDuplicates() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		leaveDialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		assertEquals(0, work.size)
		assertEquals(0, connector.leaves)
		val dialog = leaveDialog(activity)
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		assertEquals(1, work.size)
		assertEquals(0, connector.leaves)
		work.removeAt(0).run()
		assertEquals(1, connector.leaves)
		assertEquals(true, activity.isFinishing)
	}

	@Test fun failedLeaveShowsRetryAndKeepsConversationOpen() {
		val connector = MarkingConnector().apply { leaveResult = false }
		val activity = activity(connector)
		leaveDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		work.removeAt(0).run()
		assertEquals(false, activity.isFinishing)
		assertEquals(null, field(activity, "leaveDialog"))
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		val root = (field(activity, "list") as BriarRecyclerView).parent as FrameLayout
		assertEquals(
			activity.getString(R.string.connector_leave_room_failed),
			root.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text,
		)
		connector.leaveResult = true
		root.findViewById<TextView>(com.google.android.material.R.id.snackbar_action).performClick()
		(field(activity, "leaveDialog") as AlertDialog)
			.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		work.removeAt(0).run()
		assertEquals(true, activity.isFinishing)
	}

	@Test fun leaveFencesQueuedWorkAndCallbacks() {
		for (duringCall in listOf(false, true)) {
			for (change in listOf("room", "destroy", "logout")) {
				val connector = MarkingConnector()
				val activity = activity(connector)
				val invalidate = {
					when (change) {
						"room" -> ConnectorConversationActivity::class.java.getDeclaredField("threadId")
							.apply { isAccessible = true }.set(activity, "other")
						"destroy" -> (field(activity, "leaveOwner") as ConnectorConversationLoadOwner).invalidate()
						else -> connector.authorized = false
					}
				}
				leaveDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
				org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
				if (duringCall) connector.beforeLeave = invalidate else invalidate()
				work.removeAt(0).run()
				assertEquals(if (duringCall) 1 else 0, connector.leaves)
				assertEquals(false, activity.isFinishing)
			}
		}
	}

	@Test fun leaveMenuRequiresMatrixCapabilityAndAuthorization() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		val menu = android.widget.PopupMenu(activity, FrameLayout(activity)).menu
		activity.menuInflater.inflate(R.menu.connector_conversation_actions, menu)
		val action = menu.findItem(R.id.action_leave_connector_room)
		activity.onPrepareOptionsMenu(menu)
		assertEquals(true, action.isVisible)
		leaveDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		activity.onPrepareOptionsMenu(menu)
		assertEquals(false, action.isEnabled)
		connector.authorized = false
		activity.onPrepareOptionsMenu(menu)
		assertEquals(false, action.isVisible)
		activity(FakeConnector()).onPrepareOptionsMenu(menu)
		assertEquals(false, action.isVisible)
		activity(object : FakeConnector() {
			override val source get() = org.briarproject.briar.api.connector.ConnectorSource(
				"briar",
				"Briar",
			)
		}).onPrepareOptionsMenu(menu)
		assertEquals(false, action.isVisible)
	}

	@Test fun unreadSuccessClosesAndSuppressesDuplicateAndQueuedReads() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		load(activity, initial = true)
		markUnread(activity)
		markUnread(activity)
		assertEquals(2, work.size)
		work.removeAt(0).run()
		assertEquals(0, connector.marks)
		work.removeAt(0).run()
		assertEquals(1, connector.unreadMarks)
		assertEquals(true, activity.isFinishing)
	}

	@Test fun unreadFailureStaysOpenAndRestoresReadsAndRetry() {
		val connector = MarkingConnector().apply { unreadResult = false }
		val activity = activity(connector)
		markUnread(activity)
		work.removeAt(0).run()
		assertEquals(false, activity.isFinishing)
		load(activity, initial = true)
		work.removeAt(0).run()
		assertEquals(1, connector.marks)
		connector.unreadResult = true
		markUnread(activity)
		work.removeAt(0).run()
		assertEquals(true, activity.isFinishing)
	}

	@Test fun logoutDuringUnreadFencesNavigation() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		connector.beforeUnread = { connector.authorized = false }
		markUnread(activity)
		work.removeAt(0).run()
		assertEquals(false, activity.isFinishing)
	}

	@Test fun menuIsCapabilityGatedAndDisabledWhilePending() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		val menu = android.widget.PopupMenu(activity, FrameLayout(activity)).menu
		activity.menuInflater.inflate(R.menu.connector_conversation_actions, menu)
		val action = menu.findItem(R.id.action_mark_unread_connector_conversation)
		activity.onPrepareOptionsMenu(menu)
		assertEquals(true, action.isVisible)
		assertEquals(activity.getString(R.string.connector_mark_unread), action.title)
		activity.onOptionsItemSelected(action)
		activity.onPrepareOptionsMenu(menu)
		assertEquals(false, action.isEnabled)
		connector.authorized = false
		activity.onPrepareOptionsMenu(menu)
		assertEquals(false, action.isVisible)
		connector.authorized = true
		connector.enabled = false
		activity.onPrepareOptionsMenu(menu)
		assertEquals(false, action.isVisible)
		activity(FakeConnector()).onPrepareOptionsMenu(menu)
		assertEquals(false, action.isVisible)
	}

	@Test fun destroyedScreenOrChangedRoomCannotRunQueuedUnread() {
		for (changeRoom in listOf(false, true)) {
			val connector = MarkingConnector()
			val activity = activity(connector)
			markUnread(activity)
			if (changeRoom) {
				ConnectorConversationActivity::class.java.getDeclaredField("threadId")
					.apply { isAccessible = true }.set(activity, "other")
			} else {
				val owner = ConnectorConversationActivity::class.java.getDeclaredField("unreadOwner")
					.apply { isAccessible = true }.get(activity) as ConnectorConversationLoadOwner
				owner.invalidate()
			}
			work.removeAt(0).run()
			assertEquals(0, connector.unreadMarks)
			assertEquals(false, activity.isFinishing)
		}
	}

	@Test fun delayedReadCompletesBeforeUnread() {
		val connector = MarkingConnector()
		val activity = activity(connector)
		val entered = java.util.concurrent.CountDownLatch(1)
		val release = java.util.concurrent.CountDownLatch(1)
		connector.beforeRead = {
			entered.countDown()
			check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
		}
		connector.beforeUnread = { assertEquals(1, connector.marks) }
		load(activity, initial = true)
		val read = Thread(work.removeAt(0)).apply { start() }
		check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
		markUnread(activity)
		val unread = Thread(work.removeAt(0)).apply { start() }
		release.countDown()
		read.join(5000)
		unread.join(5000)
		assertEquals(false, read.isAlive)
		assertEquals(false, unread.isAlive)
		assertEquals(1, connector.unreadMarks)
		load(activity)
		work.removeAt(0).run()
		assertEquals(1, connector.marks)
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
		assertEquals(null, connector.getThreadTopic("room"))
	}

	@Test fun refreshRebindsTopicAndErrorsClearIt() {
		var topic: String? = "First\nroom"
		val connector = object : FakeConnector() {
			override fun getThreadTopic(threadId: String) = topic
		}
		val activity = activity(connector)
		val bar = requireNotNull(activity.supportActionBar)
		for (next in listOf("First\nroom", "Second room", "", null)) {
			topic = next
			load(activity)
			assertEquals(null, bar.subtitle)
			work.removeAt(0).run()
			assertEquals(ConnectorConversationActivity.topicSubtitle(next), bar.subtitle)
		}
		topic = "Restored"
		load(activity)
		work.removeAt(0).run()
		assertEquals("Restored", bar.subtitle)
		connector.result = ConnectorMessageReadResult.LoadFailed
		load(activity)
		work.removeAt(0).run()
		assertEquals(null, bar.subtitle)
	}
}
