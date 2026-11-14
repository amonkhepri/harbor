package org.briarproject.briar.android.contact

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.Success
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorRoomNotifications
import org.briarproject.briar.api.connector.ConnectorRoomNotifications.PreparedNotifications
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MatrixActiveNotificationsTest {
	private fun message(id: String, outgoing: Boolean = false) = ConnectorMessage(
		ConnectorSources.MATRIX,
		"room",
		id,
		1,
		outgoing,
		"",
		1,
		ConnectorMessageType.TEXT,
	)

	@Test fun baselineAndRepeatedEventsDoNotNotify() {
		val baseline = MatrixNotificationBaseline()
		val session = Any()
		val old = message("old")
		val incoming = message("new")
		assertTrue(baseline.update("room", session, listOf(old)).isEmpty())
		assertEquals(listOf(incoming), baseline.update("room", session, listOf(old, incoming)))
		assertTrue(baseline.update("room", session, listOf(old, incoming)).isEmpty())
		assertTrue(baseline.update("room", session, listOf(message("sent", true))).isEmpty())
		assertTrue(
			baseline.update(
				"room",
				session,
				listOf(
					message("older history").copy(sourceMessageOrder = 0),
				),
			).isEmpty(),
		)
		assertTrue(baseline.update("room", Any(), listOf(message("other account"))).isEmpty())
	}

	@Test fun sameTimestampReplayDoesNotNotifyAfterMoreThanTwoHundredEvents() {
		val baseline = MatrixNotificationBaseline()
		val session = Any()
		val initial = message("initial")
		assertTrue(baseline.update("room", session, listOf(initial)).isEmpty())
		val batch = (1..250).map { message("event-$it") }
		batch.chunked(50).forEach {
			assertEquals(it, baseline.update("room", session, it))
		}
		assertTrue(baseline.update("room", session, listOf(initial)).isEmpty())
		assertTrue(baseline.update("room", session, batch.take(50)).isEmpty())
		val newer = message("newer").copy(sourceMessageOrder = 2)
		assertEquals(listOf(newer), baseline.update("room", session, listOf(newer)))
		assertTrue(baseline.update("room", session, batch).isEmpty())
		assertTrue(baseline.update("room", session, listOf(newer)).isEmpty())
	}

	@Test fun openRoomAndPermissionSuppressNotifications() {
		assertTrue(shouldNotifyMatrixRoom("room", "other", true))
		assertFalse(shouldNotifyMatrixRoom("room", "room", true))
		assertFalse(shouldNotifyMatrixRoom("room", null, false))
	}

	@Test fun remoteEventPostsOnceAndMuteAndPermissionSuppress() {
		val app = RuntimeEnvironment.getApplication()
		val manager = app.getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(
				"contacts",
				"Messages",
				NotificationManager.IMPORTANCE_DEFAULT,
			),
		)
		val notifier = MatrixActiveNotifications(app) { it() }
		val activity = Robolectric.buildActivity(Activity::class.java)
			.setup().get()
		notifier.onActivityResumed(activity)
		val connector = Mockito.mock(
			ReadOnlyConnector::class.java,
			Mockito.withSettings().extraInterfaces(
				ConnectorRoomNotifications::class.java,
			),
		)
		val prefs = Mockito.mock(
			PreparedNotifications::class.java,
		)
		Mockito.`when`(connector.source).thenReturn(ConnectorSources.MATRIX)
		Mockito.`when`(
			(connector as ConnectorRoomNotifications)
				.prepareRoomNotifications("room"),
		).thenReturn(prefs)
		Mockito.`when`(prefs.isCurrent()).thenReturn(true)
		Mockito.`when`(prefs.readMuted()).thenReturn(false)
		val room = ConnectorThread(
			ConnectorSources.MATRIX,
			"room",
			"Room",
			0,
			"",
			false,
			ConnectorMessageType.TEXT,
		)
		val session = Any()
		fun update(id: String) {
			Mockito.`when`(connector.getRecentMessageReadResult("room", 50))
				.thenReturn(
					Success(
						listOf(message(id)),
					),
				)
			notifier.update(connector, session, listOf(room)) { true }
		}
		try {
			update("baseline")
			assertEquals(0, manager.activeNotifications.size)
			update("remote")
			assertEquals(1, manager.activeNotifications.size)
			update("remote")
			assertEquals(1, manager.activeNotifications.size)
			Mockito.`when`(prefs.readMuted()).thenReturn(true)
			update("muted")
			assertEquals(1, manager.activeNotifications.size)
			Mockito.`when`(prefs.readMuted()).thenReturn(false)
			val conversation = Mockito.mock(
				org.briarproject.briar.android.connector.ConnectorConversationActivity::class.java,
			)
			Mockito.`when`(conversation.intent).thenReturn(
				android.content.Intent()
					.putExtra("connector.SOURCE_ID", ConnectorSources.MATRIX_ID)
					.putExtra("connector.THREAD_ID", "room"),
			)
			notifier.onActivityResumed(conversation)
			update("open-room")
			assertEquals(1, manager.activeNotifications.size)
			notifier.onActivityPaused(conversation)
			update("inactive")
			assertEquals(1, manager.activeNotifications.size)
			notifier.onActivityResumed(activity)
			Shadows.shadowOf(manager).setNotificationsEnabled(false)
			update("denied")
			assertEquals(1, manager.activeNotifications.size)
		} finally {
			notifier.close()
		}
	}
}
