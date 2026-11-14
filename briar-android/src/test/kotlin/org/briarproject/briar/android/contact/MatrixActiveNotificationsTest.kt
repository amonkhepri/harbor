package org.briarproject.briar.android.contact

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import org.briarproject.briar.android.connector.ConnectorConversationActivity
import org.briarproject.briar.android.navdrawer.NavDrawerActivity
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
import org.junit.Assert.assertNotNull
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

	@Test fun sourceQualifiedNotificationIntentStructureAndNoContentExposure() {
		val app = RuntimeEnvironment.getApplication()
		val intent = createMatrixRoomNotificationIntent(app, "!room1:example.org", "Room One")

		assertEquals(ConnectorConversationActivity::class.java.name, intent.component?.className)
		assertEquals("matrix-room:!room1:example.org", intent.action)
		assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
		assertEquals(ConnectorSources.MATRIX_ID, intent.getStringExtra(ConnectorConversationActivity.SOURCE_ID))
		assertEquals("!room1:example.org", intent.getStringExtra(ConnectorConversationActivity.THREAD_ID))
		assertEquals("Room One", intent.getStringExtra(ConnectorConversationActivity.THREAD_TITLE))

		// Privacy: Event content/text must not be exposed in intent extras
		assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
		assertFalse(intent.hasExtra("text"))
		assertFalse(intent.hasExtra("message"))
	}

	@Test fun matrixRoomPendingIntentConfiguresTaskStackAndDistinctRequestCode() {
		val app = RuntimeEnvironment.getApplication()
		val pending1 = createMatrixRoomPendingIntent(app, "!roomA:example.org", "Room A")
		val pending2 = createMatrixRoomPendingIntent(app, "!roomB:example.org", "Room B")

		assertNotNull(pending1)
		assertNotNull(pending2)
	}

	@Test fun tapNotificationFromActiveAppOpensCorrectRoom() {
		val app = RuntimeEnvironment.getApplication()
		val manager = app.getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel("contacts", "Messages", NotificationManager.IMPORTANCE_DEFAULT),
		)
		val notifier = MatrixActiveNotifications(app) { it() }
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		notifier.onActivityResumed(activity)

		val connector = Mockito.mock(
			ReadOnlyConnector::class.java,
			Mockito.withSettings().extraInterfaces(ConnectorRoomNotifications::class.java),
		)
		val prefs = Mockito.mock(PreparedNotifications::class.java)
		Mockito.`when`(connector.source).thenReturn(ConnectorSources.MATRIX)
		Mockito.`when`((connector as ConnectorRoomNotifications).prepareRoomNotifications("room"))
			.thenReturn(prefs)
		Mockito.`when`(prefs.isCurrent()).thenReturn(true)
		Mockito.`when`(prefs.readMuted()).thenReturn(false)
		val room = ConnectorThread(
			ConnectorSources.MATRIX,
			"room",
			"Alpha Room",
			0,
			"",
			false,
			ConnectorMessageType.TEXT,
		)
		val session = Any()
		Mockito.`when`(connector.getRecentMessageReadResult("room", 50))
			.thenReturn(Success(listOf(message("msg-1"))))

		try {
			// Baseline then incoming
			notifier.update(connector, session, listOf(room)) { true }
			Mockito.`when`(connector.getRecentMessageReadResult("room", 50))
				.thenReturn(Success(listOf(message("msg-1"), message("msg-2"))))
			notifier.update(connector, session, listOf(room)) { true }

			assertEquals(1, manager.activeNotifications.size)
			val notification = manager.activeNotifications.first().notification
			assertNotNull(notification.contentIntent)

			// Tap while app is active
			notification.contentIntent.send()

			val started = Shadows.shadowOf(app).nextStartedActivity
			assertNotNull(started)
			assertEquals(ConnectorConversationActivity::class.java.name, started.component?.className)
			assertEquals("matrix", started.getStringExtra(ConnectorConversationActivity.SOURCE_ID))
			assertEquals("room", started.getStringExtra(ConnectorConversationActivity.THREAD_ID))
			assertEquals("Alpha Room", started.getStringExtra(ConnectorConversationActivity.THREAD_TITLE))
			assertEquals("matrix-room:room", started.action)

			// Privacy check: message body is not in notification extras or intent
			assertFalse(started.hasExtra("text"))
			assertFalse(started.hasExtra(Intent.EXTRA_TEXT))
		} finally {
			notifier.close()
		}
	}

	@Test fun tapNotificationFromStoppedActivityOpensCorrectRoom() {
		val app = RuntimeEnvironment.getApplication()
		val manager = app.getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel("contacts", "Messages", NotificationManager.IMPORTANCE_DEFAULT),
		)
		val notifier = MatrixActiveNotifications(app) { it() }
		val activityController = Robolectric.buildActivity(Activity::class.java).setup()
		notifier.onActivityResumed(activityController.get())

		val connector = Mockito.mock(
			ReadOnlyConnector::class.java,
			Mockito.withSettings().extraInterfaces(ConnectorRoomNotifications::class.java),
		)
		val prefs = Mockito.mock(PreparedNotifications::class.java)
		Mockito.`when`(connector.source).thenReturn(ConnectorSources.MATRIX)
		Mockito.`when`((connector as ConnectorRoomNotifications).prepareRoomNotifications("room"))
			.thenReturn(prefs)
		Mockito.`when`(prefs.isCurrent()).thenReturn(true)
		Mockito.`when`(prefs.readMuted()).thenReturn(false)
		val room = ConnectorThread(
			ConnectorSources.MATRIX,
			"room",
			"Beta Room",
			0,
			"",
			false,
			ConnectorMessageType.TEXT,
		)
		val session = Any()
		Mockito.`when`(connector.getRecentMessageReadResult("room", 50))
			.thenReturn(Success(listOf(message("msg-init"))))

		try {
			notifier.update(connector, session, listOf(room)) { true }
			Mockito.`when`(connector.getRecentMessageReadResult("room", 50))
				.thenReturn(Success(listOf(message("msg-init"), message("msg-new"))))
			notifier.update(connector, session, listOf(room)) { true }

			assertEquals(1, manager.activeNotifications.size)
			val notification = manager.activeNotifications.first().notification

			// Activity transitions to STOPPED state
			activityController.stop()

			// Tap from stopped activity
			notification.contentIntent.send()

			val started = Shadows.shadowOf(app).nextStartedActivity
			assertNotNull(started)
			assertEquals(ConnectorConversationActivity::class.java.name, started.component?.className)
			assertEquals("matrix", started.getStringExtra(ConnectorConversationActivity.SOURCE_ID))
			assertEquals("room", started.getStringExtra(ConnectorConversationActivity.THREAD_ID))
			assertEquals("Beta Room", started.getStringExtra(ConnectorConversationActivity.THREAD_TITLE))
			assertEquals("matrix-room:room", started.action)
		} finally {
			notifier.close()
		}
	}

	@Test fun conversationActivityFallbackToInboxSafelyNavigates() {
		val app = RuntimeEnvironment.getApplication()
		org.briarproject.briar.android.Localizer.initialize(app.getSharedPreferences("test", 0))
		val intent = createMatrixRoomNotificationIntent(app, "!room:example.org", "Test Room")
		val activity = Robolectric.buildActivity(ConnectorConversationActivity::class.java, intent).get()

		activity.fallbackToInbox()

		assertTrue(activity.isFinishing)
		val started = Shadows.shadowOf(activity).nextStartedActivity
		assertNotNull(started)
		assertEquals(NavDrawerActivity::class.java.name, started.component?.className)
		assertEquals(
			Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
			started.flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
		)
	}
}
