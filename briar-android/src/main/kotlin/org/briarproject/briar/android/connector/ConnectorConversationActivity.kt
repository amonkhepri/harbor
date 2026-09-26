package org.briarproject.briar.android.connector

import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import org.briarproject.bramble.api.lifecycle.IoExecutor
import org.briarproject.briar.R
import org.briarproject.briar.android.activity.ActivityComponent
import org.briarproject.briar.android.activity.BriarActivity
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.DISABLED
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.EMPTY
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.LOADING
import org.briarproject.briar.android.util.BriarSnackbarBuilder
import org.briarproject.briar.android.view.BriarRecyclerView
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.connector.ConnectorTextSender
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.briarproject.nullsafety.MethodsNotNullByDefault
import org.briarproject.nullsafety.ParametersNotNullByDefault
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

// A restored layoutManagerState that the async submitList callback has not
// consumed yet still holds the original scroll position. The layout manager
// has no pending state or children at that point, so its own
// onSaveInstanceState() would return an invalid anchor and overwrite it on a
// consecutive configuration change. Prefer the unconsumed restored state.
internal fun layoutManagerStateToSave(
	pendingRestoreState: Parcelable?,
	currentLayoutManagerState: Parcelable?,
): Parcelable? = pendingRestoreState ?: currentLayoutManagerState

internal class ConnectorConversationLoadOwner {
	private val generation = AtomicLong()

	fun begin(): Long = generation.incrementAndGet()

	fun owns(loadGeneration: Long): Boolean = generation.get() == loadGeneration

	fun invalidate() {
		generation.incrementAndGet()
	}
}

/**
 * Read-only timeline for one connector-neutral thread (MX-012). The Telegram
 * source built this screen first; it is now chosen at runtime via [SOURCE_ID]
 * so a Matrix inbox row reuses the same rendering, pagination and
 * availability-state handling instead of duplicating a Matrix-only Activity.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
class ConnectorConversationActivity : BriarActivity() {

	companion object {
		const val SOURCE_ID = "connector.SOURCE_ID"
		const val THREAD_ID = "connector.THREAD_ID"
		const val THREAD_TITLE = "connector.THREAD_TITLE"

		private const val MESSAGE_LIMIT = 50
		private const val REFRESH_INTERVAL_MS = 15_000L
		private const val STATE_LAYOUT_MANAGER = "layoutManager"

		private fun emptyTextForState(state: ConnectorConversationAvailabilityState): Int = when (state) {
			LOADING ->
				R.string.connector_conversation_loading
			DISABLED ->
				R.string.connector_conversation_disabled
			ACCOUNT_UNAVAILABLE ->
				R.string.connector_conversation_account_unavailable
			LOAD_FAILED ->
				R.string.connector_conversation_load_failed
			EMPTY ->
				R.string.connector_conversation_empty
		}

		private fun isManualRefreshAction(itemId: Int): Boolean =
			itemId == R.id.action_refresh_connector_conversation

		private fun shouldShowManualRefreshAction(
			connectorEnabled: Boolean,
			threadId: String,
			state: ConnectorConversationMessageListState,
		): Boolean = connectorEnabled && hasValidThreadId(threadId) && !state.isLoading

		private fun hasValidThreadId(threadId: String): Boolean = threadId.isNotEmpty()

		private fun shouldShowComposeBar(
			connectorEnabled: Boolean,
			authorized: Boolean,
			threadId: String,
			textSender: ConnectorTextSender?,
		): Boolean = textSender != null && connectorEnabled && authorized && hasValidThreadId(threadId)

		private fun titleText(title: String?, fallback: String): String =
			if (title == null || title.trim().isEmpty()) fallback else title
	}

	private val adapter = ConnectorConversationAdapter()

	@Inject
	lateinit var connectorRegistry: ConnectorRegistry

	@Inject
	@field:IoExecutor
	lateinit var ioExecutor: Executor

	private lateinit var list: BriarRecyclerView
	private lateinit var layoutManager: LinearLayoutManager
	private var layoutManagerState: Parcelable? = null
	private var connector: ReadOnlyConnector? = null
	private var textSender: ConnectorTextSender? = null
	private lateinit var sourceName: String
	private var threadId = ""
	private lateinit var composeBar: View
	private lateinit var composeText: EditText
	private lateinit var sendButton: Button
	private var sendInFlight = false
	private var messageState = ConnectorConversationMessageListState()
	private var automaticRefreshRunning = false
	private val automaticRefresh = Runnable {
		if (!automaticRefreshRunning && !messageState.isLoading) loadMessages(showLoading = false)
	}
	private val loadOwner = ConnectorConversationLoadOwner()

	override fun injectActivity(component: ActivityComponent) {
		component.inject(this)
	}

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setContentView(R.layout.activity_connector_conversation)

		val sourceId = intent.getStringExtra(SOURCE_ID)
		threadId = intent.getStringExtra(THREAD_ID) ?: ""
		connector = connectorRegistry.connectors.firstOrNull { it.source.id == sourceId }
		textSender = connector as? ConnectorTextSender
		sourceName = connector?.source?.displayName
			?: getString(R.string.connector_conversation_label)
		val title = titleText(
			intent.getStringExtra(THREAD_TITLE),
			getString(R.string.connector_conversation_title, sourceName),
		)

		supportActionBar?.apply {
			setDisplayHomeAsUpEnabled(true)
			this.title = title
			subtitle = sourceName
		}

		list = findViewById(R.id.connectorConversationList)
		layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
		list.setLayoutManager(layoutManager)
		list.setAdapter(adapter)

		composeBar = findViewById(R.id.connectorConversationComposeBar)
		composeText = findViewById(R.id.connectorConversationComposeText)
		sendButton = findViewById(R.id.connectorConversationSendButton)
		sendButton.setOnClickListener { onSendClicked() }
		updateComposeBarVisibility()

		loadMessages()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.connector_conversation_actions, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		val stateToSave =
			layoutManagerStateToSave(layoutManagerState, layoutManager.onSaveInstanceState())
		outState.putParcelable(STATE_LAYOUT_MANAGER, stateToSave)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		layoutManagerState = savedInstanceState.getParcelable(STATE_LAYOUT_MANAGER)
	}

	override fun onStart() {
		super.onStart()
		list.startPeriodicUpdate(REFRESH_INTERVAL_MS, automaticRefresh)
	}

	override fun onStop() {
		list.stopPeriodicUpdate()
		super.onStop()
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.findItem(R.id.action_refresh_connector_conversation)?.isVisible =
			shouldShowManualRefreshAction(
				connector?.isEnabled() == true,
				threadId,
				messageState,
			)
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val itemId = item.itemId
		if (itemId == android.R.id.home) {
			onBackPressed()
			return true
		} else if (isManualRefreshAction(itemId)) {
			loadMessages()
			return true
		}
		return super.onOptionsItemSelected(item)
	}

	override fun onDestroy() {
		loadOwner.invalidate()
		super.onDestroy()
	}

	private fun loadMessages(showLoading: Boolean = true) {
		automaticRefreshRunning = !showLoading
		val loadGeneration = loadOwner.begin()
		val activeConnector = connector
		if (activeConnector == null || !hasValidThreadId(threadId)) {
			showMessages(loadGeneration, ConnectorConversationMessageListState(), LOAD_FAILED)
			return
		}
		if (showLoading) {
			submitMessageState(
				ConnectorConversationMessageListState(
					messageState.messages,
					getString(emptyTextForState(LOADING), sourceName),
					isLoading = true,
				),
			)
		}
		ioExecutor.execute {
			if (!loadOwner.owns(loadGeneration)) return@execute
			try {
				if (!activeConnector.isEnabled()) {
					showMessages(loadGeneration, ConnectorConversationMessageListState(), DISABLED)
					return@execute
				}
				if (!activeConnector.isAuthorized()) {
					showMessages(
						loadGeneration,
						ConnectorConversationMessageListState(),
						ACCOUNT_UNAVAILABLE,
					)
					return@execute
				}
				if (!loadOwner.owns(loadGeneration)) return@execute
				val result = activeConnector.getRecentMessageReadResult(
					threadId,
					MESSAGE_LIMIT,
				)
				when (result) {
					is ConnectorMessageReadResult.Success -> {
						val state = ConnectorConversationMessageListState(
							ConnectorConversationMessageItem.fromMessages(result.messages),
						)
						showMessages(loadGeneration, state, EMPTY)
					}
					ConnectorMessageReadResult.LoadFailed -> {
						showMessages(
							loadGeneration,
							ConnectorConversationMessageListState(),
							LOAD_FAILED,
						)
					}
				}
			} catch (e: RuntimeException) {
				showMessages(
					loadGeneration,
					ConnectorConversationMessageListState(),
					LOAD_FAILED,
				)
			}
		}
	}

	private fun showMessages(
		loadGeneration: Long,
		state: ConnectorConversationMessageListState,
		availabilityState: ConnectorConversationAvailabilityState,
	) {
		runOnUiThread {
			if (!loadOwner.owns(loadGeneration)) return@runOnUiThread
			automaticRefreshRunning = false
			submitMessageState(
				state.withAvailabilityState(
					availabilityState,
					messageState,
					getString(emptyTextForState(availabilityState), sourceName),
				),
			)
		}
	}

	private fun submitMessageState(state: ConnectorConversationMessageListState) {
		list.setEmptyText(state.emptyText)
		adapter.submitList(state.messages) {
			if (!state.isLoading) list.showData()
			val savedLayoutManagerState = layoutManagerState
			if (savedLayoutManagerState != null) {
				// Restore the scroll position from before a config change instead of
				// jumping to the end now that the async adapter update has landed.
				layoutManager.onRestoreInstanceState(savedLayoutManagerState)
				layoutManagerState = null
			} else if (!list.recyclerView.canScrollVertically(1)) {
				list.scrollToPosition(state.messages.lastIndex)
			}
		}
		if (state.isLoading) list.showProgressBar()
		state.visibleStatusText?.takeIf { it != messageState.visibleStatusText }?.let {
			BriarSnackbarBuilder()
				.make(list, it, Snackbar.LENGTH_LONG)
				.show()
		}
		messageState = state
		invalidateOptionsMenu()
		updateComposeBarVisibility()
	}

	private fun updateComposeBarVisibility() {
		val visible = shouldShowComposeBar(
			connector?.isEnabled() == true,
			connector?.isAuthorized() == true,
			threadId,
			textSender,
		)
		composeBar.visibility = if (visible) View.VISIBLE else View.GONE
	}

	private fun onSendClicked() {
		val sender = textSender ?: return
		if (sendInFlight) return
		val text = composeText.text?.toString()?.trim().orEmpty()
		if (text.isEmpty()) return
		sendInFlight = true
		setComposeEnabled(false)
		val activeThreadId = threadId
		ioExecutor.execute {
			val sent = try {
				sender.sendText(activeThreadId, text)
			} catch (e: RuntimeException) {
				false
			}
			runOnUiThread { onSendCompleted(sent) }
		}
	}

	private fun onSendCompleted(sent: Boolean) {
		if (isFinishing || isDestroyed) return
		sendInFlight = false
		setComposeEnabled(true)
		if (sent) {
			composeText.setText("")
			loadMessages(showLoading = false)
		} else {
			BriarSnackbarBuilder()
				.make(list, getString(R.string.connector_conversation_send_failed), Snackbar.LENGTH_LONG)
				.show()
		}
	}

	private fun setComposeEnabled(enabled: Boolean) {
		composeText.isEnabled = enabled
		sendButton.isEnabled = enabled
	}
}
