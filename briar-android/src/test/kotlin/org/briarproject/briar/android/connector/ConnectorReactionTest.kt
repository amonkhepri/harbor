package org.briarproject.briar.android.connector

import android.app.Application
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.api.connector.ConnectorReactionSender
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.withSettings
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConnectorReactionTest {

	@Test
	fun reactionDialogPassesArgumentsAndProvidesChoices() {
		val args = ConnectorReactionDialogFragment.newInstance("room", "a").requireArguments()
		assertEquals("room", args.getString("threadId"))
		assertEquals("a", args.getString("messageId"))
		assertEquals(6, ConnectorReactionDialogFragment.DEFAULT_REACTIONS.size)
		assertEquals("👍", ConnectorReactionDialogFragment.DEFAULT_REACTIONS[0])
	}

	@Test
	fun confirmReactionDelegatesToSenderAndProtectsAgainstDuplicateSubmissions() {
		Localizer.initialize(RuntimeEnvironment.getApplication().getSharedPreferences("test", 0))
		val activity = Robolectric.buildActivity(ConnectorConversationActivity::class.java).get()
		val connector = mock(
			ReadOnlyConnector::class.java,
			withSettings().extraInterfaces(ConnectorReactionSender::class.java),
		)
		ConnectorConversationActivity::class.java.getDeclaredField("reactionSender").apply {
			isAccessible = true
			set(activity, connector)
		}
		val pending = mutableListOf<Runnable>()
		activity.ioExecutor = Executor { pending.add(it) }

		// Blank reaction is ignored
		activity.confirmReaction("room", "a", " ")
		activity.confirmReaction("room", "", "👍")

		// First valid reaction schedules execution
		activity.confirmReaction("room", "a", "👍")
		assertEquals(1, pending.size)

		// Duplicate in-flight submission for same (room, message, reaction) is blocked
		activity.confirmReaction("room", "a", "👍")
		assertEquals(1, pending.size)

		// Different reaction on same message can proceed
		activity.confirmReaction("room", "a", "❤️")
		assertEquals(2, pending.size)

		activity.finish()
		pending.forEach { it.run() }

		val sender = connector as ConnectorReactionSender
		verify(sender).sendReaction("room", "a", "👍")
		verify(sender).sendReaction("room", "a", "❤️")
		verifyNoMoreInteractions(sender)
	}
}
