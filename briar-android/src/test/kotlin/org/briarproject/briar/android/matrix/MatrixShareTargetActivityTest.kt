package org.briarproject.briar.android.matrix

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.ListView
import android.widget.TextView
import org.briarproject.briar.BuildConfig
import org.briarproject.briar.R
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.android.connector.ConnectorConversationActivity
import org.briarproject.briar.android.connector.ConnectorDraftStore
import org.briarproject.briar.android.navdrawer.NavDrawerActivity
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MatrixShareTargetActivityTest {
	private class FakeConnector(
		var authorized: Boolean = true,
		var threads: List<ConnectorThread> = emptyList(),
		var failing: Boolean = false,
	) : ReadOnlyConnector {
		override val source = ConnectorSources.MATRIX
		override fun isEnabled() = true
		override fun isAuthorized() = authorized
		override fun getRecentThreads(limit: Int): List<ConnectorThread> {
			check(!failing)
			return threads
		}
		override fun getRecentMessageReadResult(threadId: String, limit: Int) =
			ConnectorMessageReadResult.Success(emptyList())
	}

	private val connector = FakeConnector()
	private val application: Application get() = RuntimeEnvironment.getApplication()

	@Before
	@After
	fun cleanUp() = ConnectorDraftStore.clearAll()

	private fun room(id: String, title: String, invited: Boolean = false) = ConnectorThread(
		ConnectorSources.MATRIX,
		id,
		title,
		0,
		"",
		false,
		ConnectorMessageType.TEXT,
		isInvited = invited,
	)

	private fun share(text: String? = "https://example.org") =
		Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

	/** Shows the target without `onCreate()`, which needs the real application's Dagger graph. */
	private fun show(intent: Intent = share(), enabled: Boolean = true): MatrixShareTargetActivity {
		Localizer.initialize(application.getSharedPreferences("test", 0))
		val activity = Robolectric.buildActivity(MatrixShareTargetActivity::class.java, intent).get()
		activity.setTheme(R.style.BriarTheme)
		activity.connectorRegistry = object : ConnectorRegistry {
			override val connectors = listOf(connector)
		}
		activity.ioExecutor = Executor { it.run() }
		activity.showShareTarget(enabled)
		shadowOf(activity.mainLooper).idle()
		return activity
	}

	private fun status(activity: MatrixShareTargetActivity) =
		activity.findViewById<TextView>(R.id.matrixShareStatus).text.toString()

	private fun rooms(activity: MatrixShareTargetActivity) =
		activity.findViewById<ListView>(R.id.matrixShareRooms)

	@Test fun disabledBuildFinishesWithoutShowingRoomsOrTouchingDrafts() {
		connector.threads = listOf(room("!a:example.org", "Room A"))
		val activity = show(enabled = false)
		assertTrue(activity.isFinishing)
		assertNull(activity.findViewById<ListView>(R.id.matrixShareRooms))
		assertNull(shadowOf(application).nextStartedActivity)
	}

	@Test fun manifestTargetIsHiddenUnlessTheConnectorIsBuiltIn() {
		assertEquals(
			BuildConfig.MATRIX_CONNECTOR_ENABLED,
			application.resources.getBoolean(R.bool.matrix_connector_enabled),
		)
		val info = application.packageManager.getActivityInfo(
			ComponentName(application, MatrixShareTargetActivity::class.java),
			PackageManager.MATCH_DISABLED_COMPONENTS,
		)
		assertEquals(BuildConfig.MATRIX_CONNECTOR_ENABLED, info.enabled)
	}

	@Test fun briarAddContactShareFilterIsUntouched() {
		val handlers = application.packageManager
			.queryIntentActivities(share().setPackage(application.packageName), 0)
			.map { it.activityInfo.name }
		assertTrue(NavDrawerActivity::class.java.name in handlers)
		assertEquals(
			BuildConfig.MATRIX_CONNECTOR_ENABLED,
			MatrixShareTargetActivity::class.java.name in handlers,
		)
	}

	@Test fun nonTextOrEmptyShareFinishes() {
		assertTrue(show(share(text = " ")).isFinishing)
		assertTrue(show(share().setType("image/png")).isFinishing)
		assertTrue(show(Intent(Intent.ACTION_VIEW)).isFinishing)
	}

	@Test fun signedOutShowsGuidanceAndNoRooms() {
		connector.authorized = false
		connector.threads = listOf(room("!a:example.org", "Room A"))
		val activity = show()
		assertFalse(activity.isFinishing)
		assertEquals(application.getString(R.string.matrix_share_signed_out), status(activity))
		assertEquals(0, rooms(activity).adapter.count)
	}

	@Test fun emptyAndFailedRoomListsExplainThemselves() {
		assertEquals(application.getString(R.string.matrix_share_no_rooms), status(show()))
		connector.failing = true
		assertEquals(application.getString(R.string.matrix_share_failed), status(show()))
	}

	@Test fun chosenRoomOpensWithTheSharedTextAsDraftAndInvitesAreNotOffered() {
		connector.threads = listOf(
			room("!invite:example.org", "Invite", invited = true),
			room("!a:example.org", "Room A"),
			room("!b:example.org", "Room B"),
		)
		val activity = show()
		val list = rooms(activity)
		assertEquals(
			listOf("Room A", "Room B"),
			(0 until list.adapter.count).map {
				list.adapter.getItem(it)
			},
		)
		assertNull(shadowOf(application).nextStartedActivity)

		shadowOf(list).performItemClick(1)

		val started = shadowOf(application).nextStartedActivity
		assertEquals(ConnectorConversationActivity::class.java.name, started.component!!.className)
		assertEquals(
			ConnectorSources.MATRIX_ID,
			started.getStringExtra(ConnectorConversationActivity.SOURCE_ID),
		)
		assertEquals("!b:example.org", started.getStringExtra(ConnectorConversationActivity.THREAD_ID))
		assertEquals("Room B", started.getStringExtra(ConnectorConversationActivity.THREAD_TITLE))
		assertEquals(
			"https://example.org",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!b:example.org"),
		)
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!a:example.org"))
		assertTrue(activity.isFinishing)
	}

	@Test fun sharedTextIsAppendedToAnExistingDraft() {
		connector.threads = listOf(room("!a:example.org", "Room A"))
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!a:example.org", "See this:")
		shadowOf(rooms(show())).performItemClick(0)
		assertEquals(
			"See this:\nhttps://example.org",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!a:example.org"),
		)
	}
}
