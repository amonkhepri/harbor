package org.briarproject.briar.android.matrix

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import org.briarproject.briar.BuildConfig
import org.briarproject.briar.R
import org.briarproject.briar.android.BriarApplication
import org.briarproject.briar.android.activity.ActivityComponent
import org.briarproject.briar.android.activity.BriarActivity
import org.briarproject.briar.android.connector.ConnectorConversationActivity
import org.briarproject.briar.android.connector.ConnectorDraftStore
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import java.util.concurrent.Executor

/**
 * Share target for plain text (MX-089): the user picks a Matrix room, the shared text becomes that
 * room's draft and the room opens. Nothing is ever sent from here.
 */
class MatrixShareTargetActivity : BriarActivity() {
	internal lateinit var connectorRegistry: ConnectorRegistry
	internal lateinit var ioExecutor: Executor

	private lateinit var status: TextView
	private lateinit var rooms: ListView

	override fun injectActivity(component: ActivityComponent) {
		// Both dependencies are application-scoped, so they are read from the application
		// component instead of adding an inject method to the Java ActivityComponent.
		val applicationComponent = (application as BriarApplication).applicationComponent
		connectorRegistry = applicationComponent.connectorRegistry()
		ioExecutor = applicationComponent.ioExecutor()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		showShareTarget(BuildConfig.MATRIX_CONNECTOR_ENABLED)
	}

	internal fun showShareTarget(enabled: Boolean) {
		val text = sharedText(intent)
		if (!enabled || text == null) {
			finish()
			return
		}
		setContentView(R.layout.activity_matrix_share_target)
		status = findViewById(R.id.matrixShareStatus)
		rooms = findViewById(R.id.matrixShareRooms)
		status.setText(R.string.matrix_share_loading)
		ioExecutor.execute {
			// Null means signed out or unavailable; an exception means the rooms could not be read.
			val result = runCatching {
				connectorRegistry.connectors
					.firstOrNull { it.source.id == ConnectorSources.MATRIX_ID }
					?.takeIf { it.isEnabled() && it.isAuthorized() }
					?.getRecentThreads(ROOM_LIMIT)
					?.filterNot { it.isInvited }
			}
			runOnUiThread { if (!isFinishing && !isDestroyed) showRooms(result, text) }
		}
	}

	private fun showRooms(result: Result<List<ConnectorThread>?>, text: String) {
		val threads = result.getOrNull().orEmpty()
		status.setText(
			when {
				result.isFailure -> R.string.matrix_share_failed
				result.getOrNull() == null -> R.string.matrix_share_signed_out
				threads.isEmpty() -> R.string.matrix_share_no_rooms
				else -> R.string.matrix_share_choose_room
			},
		)
		rooms.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, threads.map { it.title })
		rooms.setOnItemClickListener { _, _, position, _ -> openRoom(threads[position], text) }
	}

	private fun openRoom(thread: ConnectorThread, text: String) {
		// Keep whatever the user had already typed in that room and add the shared text after it.
		val existing = ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, thread.threadId)
		val draft = if (existing.isNullOrEmpty()) text else "$existing\n$text"
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, thread.threadId, draft)
		startActivity(
			Intent(this, ConnectorConversationActivity::class.java)
				.putExtra(ConnectorConversationActivity.SOURCE_ID, ConnectorSources.MATRIX_ID)
				.putExtra(ConnectorConversationActivity.THREAD_ID, thread.threadId)
				.putExtra(ConnectorConversationActivity.THREAD_TITLE, thread.title),
		)
		finish()
	}

	internal companion object {
		private const val ROOM_LIMIT = 100

		/** The shared plain text, or null when the intent is not a usable `text/plain` share. */
		fun sharedText(intent: Intent?): String? {
			if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
			return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
		}
	}
}
