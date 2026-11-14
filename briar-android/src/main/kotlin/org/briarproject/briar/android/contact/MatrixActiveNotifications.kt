package org.briarproject.briar.android.contact

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import org.briarproject.briar.R
import org.briarproject.briar.android.connector.ConnectorConversationActivity
import org.briarproject.briar.android.util.BriarNotificationBuilder
import org.briarproject.briar.api.android.AndroidNotificationManager.CONTACT_CHANNEL_ID
import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.Success
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorRoomNotifications
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector

/** Initial snapshots and repeated callbacks never alert. Failed reads leave the baseline intact. */
internal class MatrixNotificationBaseline {
	private var fence: Any? = null
	private val seen = HashMap<String, Set<String>>()
	private val latestOrder = HashMap<String, Long>()

	fun update(room: String, session: Any?, messages: List<ConnectorMessage>): List<ConnectorMessage> {
		if (fence !== session) {
			fence = session
			seen.clear()
			latestOrder.clear()
		}
		val ids = messages.mapTo(LinkedHashSet()) { it.messageId }
		val previous = seen[room]
		val cutoff = latestOrder[room] ?: Long.MIN_VALUE
		latestOrder[room] = maxOf(cutoff, messages.maxOfOrNull { it.sourceMessageOrder } ?: cutoff)
		seen[room] = ((previous.orEmpty() + ids).toList().takeLast(200)).toSet()
		if (previous == null) return emptyList()
		return messages.filter {
			it.messageId !in previous &&
				it.sourceMessageOrder >= cutoff &&
				!it.isOutgoing &&
				it.type != ConnectorMessageType.DELETED
		}
	}

	fun retain(rooms: Set<String>) {
		seen.keys.retainAll(rooms)
		latestOrder.keys.retainAll(rooms)
	}
}

/** Uses the inbox's existing event-driven refreshes; owns no sync loop or background service. */
internal class MatrixActiveNotifications(
	private val app: Application,
	private val onUiThread: (() -> Unit) -> Unit,
) : Application.ActivityLifecycleCallbacks {
	private val baseline = MatrixNotificationBaseline()
	private var resumed: Activity? = null
	private var closed = false

	init {
		app.registerActivityLifecycleCallbacks(this)
	}

	// Called by the inbox IO load. Final eligibility is checked again on the main thread.
	fun update(
		connector: ReadOnlyConnector,
		fence: Any?,
		threads: List<ConnectorThread>,
		isCurrent: () -> Boolean,
	) {
		if (connector.source != ConnectorSources.MATRIX) return
		val rooms = threads.filterNot { it.isInvited }
		baseline.retain(rooms.mapTo(HashSet()) { it.threadId })
		rooms.forEach { room ->
			val result = connector.getRecentMessageReadResult(room.threadId, 50) as? Success
				?: return@forEach
			val incoming = baseline.update(room.threadId, fence, result.messages)
			if (incoming.isEmpty()) return@forEach
			val preferences = (connector as? ConnectorRoomNotifications)
				?.prepareRoomNotifications(room.threadId) ?: return@forEach
			if (preferences.readMuted() != false) return@forEach
			onUiThread {
				if (!closed && isCurrent() && preferences.isCurrent()) {
					incoming.forEach { post(room, it) }
				}
			}
		}
	}

	private fun post(room: ConnectorThread, message: ConnectorMessage) {
		val activity = resumed ?: return
		val openRoom = if (activity is ConnectorConversationActivity &&
			activity.intent.getStringExtra(ConnectorConversationActivity.SOURCE_ID) ==
			ConnectorSources.MATRIX_ID
		) {
			activity.intent.getStringExtra(ConnectorConversationActivity.THREAD_ID)
		} else {
			null
		}
		val manager = NotificationManagerCompat.from(app)
		if (!shouldNotifyMatrixRoom(room.threadId, openRoom, manager.areNotificationsEnabled())) return
		val intent = Intent(app, ConnectorConversationActivity::class.java)
			.putExtra(ConnectorConversationActivity.SOURCE_ID, ConnectorSources.MATRIX_ID)
			.putExtra(ConnectorConversationActivity.THREAD_ID, room.threadId)
			.putExtra(ConnectorConversationActivity.THREAD_TITLE, room.title)
			.setAction("matrix-room:${room.threadId}")
		val pending = PendingIntent.getActivity(
			app,
			0,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
		val notification = BriarNotificationBuilder(app, CONTACT_CHANNEL_ID)
			.setSmallIcon(R.drawable.notification_private_message)
			.setContentTitle(app.getString(R.string.app_name))
			.setContentText(
				app.resources.getQuantityString(R.plurals.private_message_notification_text, 1, 1),
			)
			.setContentIntent(pending)
			.build()
		try {
			manager.notify("matrix:${room.threadId}:${message.messageId}", 0, notification)
		} catch (e: SecurityException) {
			// Permission can be revoked between the check and notify().
		}
	}

	fun close() {
		closed = true
		resumed = null
		app.unregisterActivityLifecycleCallbacks(this)
	}

	override fun onActivityResumed(activity: Activity) {
		resumed = activity
	}
	override fun onActivityPaused(activity: Activity) {
		if (resumed === activity) resumed = null
	}
	override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
	override fun onActivityStarted(activity: Activity) = Unit
	override fun onActivityStopped(activity: Activity) = Unit
	override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
	override fun onActivityDestroyed(activity: Activity) = Unit
}

internal fun shouldNotifyMatrixRoom(room: String, openRoom: String?, permitted: Boolean): Boolean =
	permitted && room != openRoom
