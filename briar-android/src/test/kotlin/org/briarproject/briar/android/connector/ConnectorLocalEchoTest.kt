package org.briarproject.briar.android.connector

import android.app.Application
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
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorTextSender
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.Assert.assertEquals
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
		state = state.completeTextSend(echo.stableId, true).reconcile(listOf(old))
		assertEquals(echo, state.messages.last())
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
		state = state.completeTextSend(id, false).reconcile(emptyList())
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

	@Test fun unrelatedOrHistoricalRowsDoNotConsumeEcho() {
		var state = ConnectorConversationMessageListState()
			.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
		state = state.completeTextSend(state.messages.last().stableId, true)
		for (unrelated in listOf(
			server.copy(isOutgoing = false),
			server.copy(connectorThreadId = "other"),
			server.copy(dateMillis = 1_000),
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
		state = state.completeTextSend(failedId, false)
			.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
		state = state.completeTextSend(state.messages.last().stableId, true)
			.reconcile(listOf(server))
		assertEquals(listOf(server), state.messages.filter { it.localEchoStatus == null })
		assertEquals(failedId, state.localEchoes.single().item.stableId)
		assertEquals(ConnectorLocalEchoStatus.FAILED, state.messages.last().localEchoStatus)
	}

	@Test fun oneServerMessageCannotConsumeTwoIdenticalSendsAcrossReloads() {
		var state = ConnectorConversationMessageListState()
		repeat(2) {
			state = state.beginTextSend(ConnectorSources.MATRIX, "room", "hello", 2_000)
			state = state.completeTextSend(state.messages.last().stableId, true)
		}
		state = state.reconcile(listOf(server)).reconcile(listOf(server))
		assertEquals(1, state.localEchoes.size)
		assertEquals(2, state.messages.size)
		state = state.reconcile(listOf(server, server.copy(connectorMessageId = "second")))
		assertTrue(state.localEchoes.isEmpty())
	}
}
