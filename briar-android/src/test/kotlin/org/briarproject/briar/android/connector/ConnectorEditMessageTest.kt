package org.briarproject.briar.android.connector

import android.app.Application
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.api.connector.ConnectorMessageEditor
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verify
import org.mockito.Mockito.withSettings
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConnectorEditMessageTest {
	@Test
	fun markMessageEditedReplacesOnlyTheConfirmedTextRow() {
		val target = item("a")
		val echo = item("a").copy(localEchoStatus = ConnectorLocalEchoStatus.SENT)
		val other = item("b")
		val state = ConnectorConversationMessageListState(messages = listOf(target, echo, other))

		val updated = markMessageEdited(state, "room", "a", "fixed").messages

		assertEquals(target.copy(text = "fixed", isEdited = true), updated[0])
		assertEquals(echo, updated[1])
		assertEquals(other, updated[2])
	}

	@Test
	fun editDialogRestoresTargetAndOnlyNonBlankTextReachesTheEditor() {
		val args = ConnectorEditMessageDialogFragment.newInstance("room", "a", "typo").requireArguments()
		assertEquals("typo", args.getString("text"))
		Localizer.initialize(RuntimeEnvironment.getApplication().getSharedPreferences("test", 0))
		val activity = Robolectric.buildActivity(ConnectorConversationActivity::class.java).get()
		val connector = mock(
			ReadOnlyConnector::class.java,
			withSettings().extraInterfaces(ConnectorMessageEditor::class.java),
		)
		ConnectorConversationActivity::class.java.getDeclaredField("connector").apply {
			isAccessible = true
			set(activity, connector)
		}
		val pending = mutableListOf<Runnable>()
		activity.ioExecutor = Executor { pending.add(it) }

		activity.confirmEditMessage("room", "a", " ")
		activity.confirmEditMessage(args.getString("threadId")!!, args.getString("messageId")!!, "fixed")
		activity.finish()
		pending.forEach { it.run() }

		val editor = connector as ConnectorMessageEditor
		verify(editor).editTextMessage("room", "a", "fixed")
		verifyNoMoreInteractions(editor)
	}

	private fun item(id: String) = ConnectorConversationMessageItem(
		connectorSource = ConnectorSources.MATRIX,
		connectorThreadId = "room",
		connectorMessageId = id,
		dateMillis = 1L,
		isOutgoing = true,
		text = "typo",
		type = ConnectorMessageType.TEXT,
	)
}
