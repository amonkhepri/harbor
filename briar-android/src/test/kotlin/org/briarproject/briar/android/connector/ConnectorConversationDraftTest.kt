package org.briarproject.briar.android.connector

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.recyclerview.widget.LinearLayoutManager
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.R
import org.briarproject.briar.android.matrix.MatrixSetupViewModel
import org.briarproject.briar.android.view.BriarRecyclerView
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorRoomLeaver
import org.briarproject.briar.api.connector.ConnectorRoomLeaver.PreparedLeave
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorTextSender
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.Snapshot
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorConversationDraftTest {

	@get:Rule val rule = InstantTaskExecutorRule()

	private class FakeConnector(
		var enabled: Boolean = true,
		var authorized: Boolean = true,
		var sendResult: Boolean = true,
	) : ReadOnlyConnector,
		ConnectorTextSender,
		ConnectorRoomLeaver {
		override val source = ConnectorSources.MATRIX
		override fun isEnabled() = enabled
		override fun isAuthorized() = authorized
		override fun getRecentThreads(limit: Int) = emptyList<ConnectorThread>()
		override fun getRecentMessageReadResult(threadId: String, limit: Int) =
			ConnectorMessageReadResult.Success(emptyList())

		var sendCount = 0
		var lastSentText: String? = null
		override fun sendText(threadId: String, text: String): Boolean {
			sendCount++
			lastSentText = text
			return sendResult
		}

		var leaveCount = 0
		override fun prepareLeave(threadId: String): PreparedLeave = object : PreparedLeave {
			override fun isCurrent() = true
			override fun leave(): Boolean {
				leaveCount++
				return true
			}
		}
	}

	private val work = mutableListOf<Runnable>()
	private val fakeConnector = FakeConnector()

	@Before
	@After
	fun cleanUp() {
		ConnectorDraftStore.clearAll()
	}

	private fun createActivity(
		sourceId: String = ConnectorSources.MATRIX_ID,
		threadId: String = "!room1:matrix.org",
	): ConnectorConversationActivity {
		Localizer.initialize(RuntimeEnvironment.getApplication().getSharedPreferences("test", 0))
		val intent = Intent().apply {
			putExtra(ConnectorConversationActivity.SOURCE_ID, sourceId)
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
		composeText.addTextChangedListener(object : android.text.TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
			override fun afterTextChanged(s: android.text.Editable?) {
				activity.saveDraft()
			}
		})

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

	private fun pause(activity: ConnectorConversationActivity) {
		activity.saveDraft()
	}

	private fun setField(target: Any, name: String, value: Any?) {
		var currentClass: Class<*>? = target.javaClass
		while (currentClass != null) {
			try {
				val field = currentClass.getDeclaredField(name)
				field.isAccessible = true
				field.set(target, value)
				return
			} catch (_: NoSuchFieldException) {
				currentClass = currentClass.superclass
			}
		}
		error("Field $name not found in $target")
	}

	private fun getComposeText(activity: ConnectorConversationActivity): ConnectorComposeText =
		ConnectorConversationActivity::class.java.getDeclaredField("composeText").apply {
			isAccessible = true
		}.get(activity) as ConnectorComposeText

	@Test
	fun preservesDraftAcrossRoomNavigationWithoutCrossRoomLeak() {
		// 1. Enter Room 1 and type draft
		val activity1 = createActivity(threadId = "!room1:matrix.org")
		val composeText1 = getComposeText(activity1)
		composeText1.setText("Draft in room 1")
		pause(activity1)

		assertEquals(
			"Draft in room 1",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)

		// 2. Navigate away to Room 2
		val activity2 = createActivity(threadId = "!room2:matrix.org")
		activity2.restoreDraft(null)
		val composeText2 = getComposeText(activity2)

		// Assert no cross-room leak: Room 2 has empty draft
		assertEquals("", composeText2.text.toString())
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room2:matrix.org"))

		// Type in Room 2 and navigate away
		composeText2.setText("Draft in room 2")
		pause(activity2)
		assertEquals(
			"Draft in room 2",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room2:matrix.org"),
		)

		// 3. Return to Room 1: draft reappears with cursor at the end
		val activity1Return = createActivity(threadId = "!room1:matrix.org")
		activity1Return.restoreDraft(null)
		val composeText1Return = getComposeText(activity1Return)

		assertEquals("Draft in room 1", composeText1Return.text.toString())
		assertEquals(composeText1Return.text?.length, composeText1Return.selectionStart)
		assertEquals(composeText1Return.text?.length, composeText1Return.selectionEnd)

		// 4. Return to Room 2: Room 2 draft also preserved
		val activity2Return = createActivity(threadId = "!room2:matrix.org")
		activity2Return.restoreDraft(null)
		val composeText2Return = getComposeText(activity2Return)
		assertEquals("Draft in room 2", composeText2Return.text.toString())
	}

	@Test
	fun draftRestorationDoesNotAdvertiseProgrammaticTyping() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Saved draft")

		val activity = createActivity(threadId = "!room1:matrix.org")
		val composeText = getComposeText(activity)

		var typingAdvertised = false
		composeText.onUserEdit = { typingAdvertised = it }

		activity.restoreDraft(null)

		assertEquals("Saved draft", composeText.text.toString())
		// Programmatic restoration must NOT advertise typing
		assertFalse(typingAdvertised)
	}

	@Test
	fun sendingDraftClearsItFromStore() {
		val activity = createActivity(threadId = "!room1:matrix.org")
		val composeText = getComposeText(activity)
		composeText.setText("Ready to send")
		activity.saveDraft()

		assertEquals(
			"Ready to send",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)

		// Send message
		val onSend = ConnectorConversationActivity::class.java.getDeclaredMethod("onSendClicked")
		onSend.isAccessible = true
		onSend.invoke(activity)

		// Draft is immediately cleared from store
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))

		// Complete sending work
		assertEquals(1, work.size)
		work.removeAt(0).run()

		assertEquals(1, fakeConnector.sendCount)
		assertEquals("Ready to send", fakeConnector.lastSentText)

		// Reopen room: draft is gone
		val reopened = createActivity(threadId = "!room1:matrix.org")
		reopened.restoreDraft(null)
		assertEquals("", getComposeText(reopened).text.toString())
	}

	@Test
	fun clearingDraftTextClearsStore() {
		val activity = createActivity(threadId = "!room1:matrix.org")
		val composeText = getComposeText(activity)
		composeText.setText("Some draft")
		assertEquals(
			"Some draft",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)

		// User deletes text
		composeText.setText("")
		pause(activity)

		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))

		val reopened = createActivity(threadId = "!room1:matrix.org")
		reopened.restoreDraft(null)
		assertEquals("", getComposeText(reopened).text.toString())
	}

	@Test
	fun rotationRestorationDoesNotInterfereWithAndroidViewState() {
		val activity = createActivity(threadId = "!room1:matrix.org")
		val composeText = getComposeText(activity)
		composeText.setText("Draft before rotation")
		activity.saveDraft()

		// During rotation, state is not null
		val rotatedActivity = createActivity(threadId = "!room1:matrix.org")
		val rotatedComposeText = getComposeText(rotatedActivity)
		// Initially empty in new activity instance
		assertEquals("", rotatedComposeText.text.toString())

		// restoreDraft with state != null should NOT overwrite
		rotatedActivity.restoreDraft(Bundle())
		assertEquals("", rotatedComposeText.text.toString())

		// Store still retains draft for subsequent navigation
		assertEquals(
			"Draft before rotation",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)
	}

	@Test
	fun leavingRoomDiscardsDraftDespitePauseOnFinish() {
		val activity = createActivity(threadId = "!room1:matrix.org")
		val composeText = getComposeText(activity)
		composeText.setText("Draft before leaving")
		pause(activity)

		assertEquals(
			"Draft before leaving",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)

		val confirmLeaveRoom =
			ConnectorConversationActivity::class.java.getDeclaredMethod("confirmLeaveRoom")
		confirmLeaveRoom.isAccessible = true
		confirmLeaveRoom.invoke(activity)
		val leaveDialogField =
			ConnectorConversationActivity::class.java.getDeclaredField("leaveDialog")
		leaveDialogField.isAccessible = true
		(leaveDialogField.get(activity) as AlertDialog)
			.getButton(AlertDialog.BUTTON_POSITIVE)
			.performClick()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

		assertEquals(1, work.size)
		work.removeAt(0).run()

		assertEquals(1, fakeConnector.leaveCount)
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))

		// finish() schedules onPause(), whose saveDraft() must not resurrect the draft that
		// leaving the room just cleared.
		pause(activity)

		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))
	}

	@Test
	fun matrixLogoutClearsMatrixDraftsWithoutAccidentalSend() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Matrix draft")
		ConnectorDraftStore.setDraft(ConnectorSources.TELEGRAM_ID, "chat1", "Telegram draft")

		var logoutCalled = false
		val session = object : MatrixAuthSession {
			override fun getSnapshot() = Snapshot(MatrixAuthState.READY, RecoverableErrorDetail.NONE)
			override fun start() {}
			override fun submitHomeserver(homeserverUrl: String) {}
			override fun submitCredentials(username: String, password: String) {}
			override fun close() {}
			override fun logout() {
				logoutCalled = true
			}
		}

		val directExecutor = Executor { it.run() }
		val model = MatrixSetupViewModel(session, directExecutor, true)
		model.start()
		model.logout(true)

		assertTrue(logoutCalled)
		// Matrix draft is cleared
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))
		// Telegram draft is preserved
		assertEquals(
			"Telegram draft",
			ConnectorDraftStore.getDraft(ConnectorSources.TELEGRAM_ID, "chat1"),
		)
		// Connector send was NOT called (no accidental send on logout)
		assertEquals(0, fakeConnector.sendCount)
	}
}
