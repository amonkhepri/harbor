package org.briarproject.briar.android.connector

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.ActionBar
import androidx.lifecycle.ViewModelProvider
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
import org.briarproject.briar.android.util.ActivityLaunchers.GetImageAdvanced
import org.briarproject.briar.android.util.ActivityLaunchers.OpenImageDocumentAdvanced
import org.briarproject.briar.android.util.BriarSnackbarBuilder
import org.briarproject.briar.android.util.UiUtils.launchActivityToOpenFile
import org.briarproject.briar.android.view.BriarRecyclerView
import org.briarproject.briar.api.connector.ConnectorFileSender
import org.briarproject.briar.api.connector.ConnectorImageSender
import org.briarproject.briar.api.connector.ConnectorMessageDeleter
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReadMarker
import org.briarproject.briar.api.connector.ConnectorRoomLeaver
import org.briarproject.briar.api.connector.ConnectorRoomObserver
import org.briarproject.briar.api.connector.ConnectorTypingObserver
import org.briarproject.briar.api.connector.ConnectorUnreadMarker
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorTextSender
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.briarproject.nullsafety.MethodsNotNullByDefault
import org.briarproject.nullsafety.ParametersNotNullByDefault
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
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

internal class ConnectorConversationRetryOwner {
	private val generations = ConcurrentHashMap<String, Long>()
	private val invalidated = AtomicBoolean(false)

	fun begin(echoId: String): Long {
		if (invalidated.get()) return -1L
		return generations.compute(echoId) { _, cur -> (cur ?: 0L) + 1L } ?: -1L
	}

	fun owns(echoId: String, gen: Long): Boolean =
		!invalidated.get() && gen != -1L && generations[echoId] == gen

	fun clear(echoId: String) {
		generations.remove(echoId)
	}
	fun invalidate() {
		invalidated.set(true)
		generations.clear()
	}
}

/**
 * Timeline for one connector-neutral thread (MX-012), read-only unless the connector also
 * implements [ConnectorTextSender] (MX-013), in which case a compose bar is shown for sending
 * into the open thread, and/or [ConnectorImageSender] (MX-014), in which case an attach-image
 * button next to the compose bar launches the system image picker and sends the chosen image. The
 * Telegram source built this screen first; it is now chosen at runtime via [SOURCE_ID] so a Matrix
 * inbox row reuses the same rendering, pagination and availability-state handling instead of
 * duplicating a Matrix-only Activity. When the connector also implements [ConnectorReadMarker]
 * (MX-019), opening this screen marks the thread read once, and a periodic/manual refresh that
 * loads at least one message marks it read again.
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
		internal const val TYPING_EXPIRY_MS = 5_000L
		private const val STATE_LAYOUT_MANAGER = "layoutManager"
		private const val DEFAULT_IMAGE_FILE_EXTENSION = "jpg"
		private const val STATE_PENDING_IMAGE = "pendingImage"
		private const val STATE_FIND_VISIBLE = "findVisible"

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

		private fun isFindAction(itemId: Int): Boolean = itemId == R.id.action_find_connector_conversation

		private fun shouldShowFindAction(sourceId: String?): Boolean =
			sourceId == ConnectorSources.MATRIX_ID

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

		private fun shouldShowAttachButton(
			connectorEnabled: Boolean,
			authorized: Boolean,
			threadId: String,
			imageSender: ConnectorImageSender?,
		): Boolean = imageSender != null && connectorEnabled && authorized && hasValidThreadId(threadId)

		internal fun bindTopic(actionBar: ActionBar?, topic: String?) {
			actionBar?.subtitle = topicSubtitle(topic)
		}

		internal fun topicSubtitle(topic: String?): String? {
			val text = StringBuilder()
			var index = 0
			while (topic != null && index < topic.length) {
				val point = topic.codePointAt(index)
				index += Character.charCount(point)
				when {
					Character.isWhitespace(point) || Character.isSpaceChar(point) -> {
						if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
					}
					Character.getType(point).toByte() !in
						listOf(Character.CONTROL, Character.FORMAT, Character.SURROGATE) ->
						text.appendCodePoint(point)
				}
			}
			val normalized = text.toString().trim()
			if (normalized.isEmpty()) return null
			return if (normalized.codePointCount(0, normalized.length) > 120) {
				normalized.substring(0, normalized.offsetByCodePoints(0, 119)).trimEnd() + "…"
			} else {
				normalized
			}
		}

		internal fun titleText(title: String?, fallback: String): String {
			if (title == null) return fallback
			var index = 0
			while (index < title.length) {
				// Walk code points so visible supplementary-plane emoji stay intact.
				val codePoint = title.codePointAt(index)
				val type = Character.getType(codePoint).toByte()
				if (!Character.isWhitespace(codePoint) &&
					!Character.isSpaceChar(codePoint) &&
					type != Character.FORMAT &&
					type != Character.CONTROL &&
					type != Character.SURROGATE
				) {
					return title
				}
				index += Character.charCount(codePoint)
			}
			return fallback
		}
	}

	private val adapter =
		ConnectorConversationAdapter(::onDeleteMessageClicked, ::onRetrySendClicked)

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
	private var imageSender: ConnectorImageSender? = null
	private var fileSender: ConnectorFileSender? = null
	private var readMarker: ConnectorReadMarker? = null
	private val readStateLock = Any()
	private val unreadOwner = ConnectorConversationLoadOwner()
	private val leaveOwner = ConnectorConversationLoadOwner()
	private var leavePending = false
	private var leaveDialog: AlertDialog? = null

	@Volatile private var unreadPending = false
	private lateinit var fileButton: ImageButton
	private lateinit var fileUpload: ConnectorFileUploadState
	private val fileLauncher =
		registerForActivityResult(ActivityResultContracts.GetContent(), ::onFileChosen)
	private lateinit var sourceName: String
	private var threadId = ""
	private lateinit var composeBar: View
	private lateinit var composeText: EditText
	private var typing: ConnectorTypingController? = null
	private var composerResumed = false
	private lateinit var sendButton: Button
	private lateinit var attachButton: ImageButton
	private var sendInFlight = false
	private lateinit var imageUpload: ConnectorImageUploadState
	private lateinit var findBar: View
	private lateinit var findQuery: EditText
	private lateinit var findCount: TextView
	private lateinit var findPrevious: ImageButton
	private lateinit var findNext: ImageButton
	private var searchState = ConnectorConversationSearchState()

	// Not restored across recreation: matches MX-015's local-only echo scope boundary.
	private var pendingImageEchoId: String? = null
	private var pendingFileEchoId: String? = null
	private val inFlightRetryIds = mutableSetOf<String>()
	private val retryOwner = ConnectorConversationRetryOwner()

	private val imageContentLauncher = registerForActivityResult(GetImageAdvanced(), ::onImageChosen)
	private val imageDocumentLauncher =
		registerForActivityResult(OpenImageDocumentAdvanced(), ::onImageChosen)
	private var messageState = ConnectorConversationMessageListState()
	private var retryRequested = false
	private val retryCoalescer = ConnectorRoomUpdateCoalescer {
		runOnUiThread {
			if (!isFinishing && !isDestroyed) {
				loadMessages(showLoading = messageState.messages.isEmpty())
			}
		}
	}
	private var loadFailedSnackbar: Snackbar? = null
	private var lastLoadFailed = false
	private var roomObserverSubscription: ConnectorRoomObserver.RoomSubscription? = null
	private var roomUpdateCoalescer: ConnectorRoomUpdateCoalescer? = null
	private lateinit var typingIndicator: TextView
	private val typingIndicatorHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private val typingExpiryRunnable = Runnable { clearTypingIndicator() }
	private var typingObserverSubscription: ConnectorTypingObserver.TypingSubscription? = null
	private var typingGeneration: Any? = null
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
		imageUpload = ViewModelProvider(this)[ConnectorImageUploadState::class.java]
		imageUpload.restore(state?.getString(STATE_PENDING_IMAGE))
		connector = connectorRegistry.connectors.firstOrNull { it.source.id == sourceId }
		textSender = connector as? ConnectorTextSender
		imageSender = connector as? ConnectorImageSender
		fileSender = connector as? ConnectorFileSender
		readMarker = connector as? ConnectorReadMarker
		fileUpload = ViewModelProvider(this)[ConnectorFileUploadState::class.java]
		sourceName = connector?.source?.displayName
			?: getString(R.string.connector_conversation_label)
		val title = titleText(
			intent.getStringExtra(THREAD_TITLE),
			getString(R.string.connector_conversation_title, sourceName),
		)

		supportActionBar?.apply {
			setDisplayHomeAsUpEnabled(true)
			this.title = title
			subtitle = null
		}

		list = findViewById(R.id.connectorConversationList)
		layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
		list.setLayoutManager(layoutManager)
		list.setAdapter(adapter)
		list.findViewById<View>(R.id.emptyAction)?.setOnClickListener { retryRefresh() }
		typingIndicator = findViewById(R.id.connectorConversationTypingIndicator)

		findBar = findViewById(R.id.connectorConversationFindBar)
		findQuery = findViewById(R.id.connectorConversationFindQuery)
		findCount = findViewById(R.id.connectorConversationFindCount)
		findPrevious = findViewById(R.id.connectorConversationFindPrevious)
		findNext = findViewById(R.id.connectorConversationFindNext)
		findQuery.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
			override fun afterTextChanged(s: Editable?) = onFindQueryChanged(s?.toString().orEmpty())
		})
		findPrevious.setOnClickListener { navigateFind(forward = false) }
		findNext.setOnClickListener { navigateFind(forward = true) }
		// EditText restores its own typed text across recreation by default; restore the find
		// bar's visibility to match so a rotation can't leave that restored query search active
		// but hidden.
		if (state?.getBoolean(STATE_FIND_VISIBLE) == true) findBar.visibility = View.VISIBLE

		composeBar = findViewById(R.id.connectorConversationComposeBar)
		composeText = findViewById(R.id.connectorConversationComposeText)
		composeText.hint = getString(R.string.connector_conversation_compose_hint, sourceName)
		restoreDraft(state)
		composeText.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
			override fun afterTextChanged(s: Editable?) {
				if (!sendInFlight) {
					saveDraft()
				}
			}
		})
		val typingSender = connector as? org.briarproject.briar.api.connector.ConnectorTypingSender
		if (typingSender != null) {
			val handler = android.os.Handler(android.os.Looper.getMainLooper())
			typing = ConnectorTypingController(
				{ typingSender.prepareTyping(threadId) },
				{
					composerResumed &&
						composeText.hasFocus() &&
						composeText.hasWindowFocus() &&
						composeText.isEnabled &&
						!sendInFlight &&
						textSender != null &&
						connector?.isEnabled() == true &&
						connector?.isAuthorized() == true &&
						hasValidThreadId(threadId)
				},
				android.os.SystemClock::elapsedRealtime,
				{ task, delay -> handler.postDelayed(task, delay) },
				{ handler.removeCallbacks(it) },
			)
			(composeText as? ConnectorComposeText)?.onUserEdit = { typing?.edited(it) }
			composeText.setOnFocusChangeListener { _, focused -> if (!focused) typing?.stop() }
		}
		sendButton = findViewById(R.id.connectorConversationSendButton)
		sendButton.setOnClickListener { onSendClicked() }
		attachButton = findViewById(R.id.connectorConversationAttachButton)
		attachButton.setOnClickListener { onAttachImageClicked() }
		fileButton = findViewById(R.id.connectorConversationFileButton)
		fileButton.setOnClickListener {
			if (!canSendFile() || sendInFlight) return@setOnClickListener
			val prepared = fileSender?.prepareFileSend(threadId)
			if (prepared == null) {
				fileUpload.complete(false)
				return@setOnClickListener
			}
			fileUpload.pick(prepared)
			try {
				fileLauncher.launch("*/*")
			} catch (e: RuntimeException) {
				fileUpload.choose(false)
				fileUpload.complete(false)
			}
		}
		fileUpload.status.observe(this) { status ->
			sendInFlight = status == ConnectorFileUploadState.Status.PICKING ||
				status == ConnectorFileUploadState.Status.SENDING
			setComposeEnabled(!sendInFlight)
			if (status == ConnectorFileUploadState.Status.SENT ||
				status == ConnectorFileUploadState.Status.FAILED
			) {
				completeFileEcho(status == ConnectorFileUploadState.Status.SENT)
				BriarSnackbarBuilder().make(
					list,
					if (status == ConnectorFileUploadState.Status.SENT) {
						R.string.connector_file_sent
					} else {
						R.string.connector_file_failed
					},
					Snackbar.LENGTH_LONG,
				).show()
				if (status == ConnectorFileUploadState.Status.SENT) loadMessages(showLoading = false)
				fileUpload.acknowledge()
			}
		}
		updateComposeBarVisibility()
		imageUpload.status.observe(this) { status ->
			when (status) {
				ConnectorImageUploadState.Status.SENDING -> {
					sendInFlight = true
					setComposeEnabled(false)
				}
				ConnectorImageUploadState.Status.SENT -> {
					completeImageEcho(true)
					onSendCompleted(true, false)
					imageUpload.acknowledge()
				}
				ConnectorImageUploadState.Status.FAILED -> {
					completeImageEcho(false)
					onSendCompleted(false, false)
					imageUpload.acknowledge()
				}
				else -> Unit
			}
		}

		loadMessages(isInitialOpen = true)
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
		outState.putString(STATE_PENDING_IMAGE, imageUpload.pendingImage)
		outState.putBoolean(STATE_FIND_VISIBLE, findBar.visibility == View.VISIBLE)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		layoutManagerState = savedInstanceState.getParcelable(STATE_LAYOUT_MANAGER)
	}

	override fun onStart() {
		super.onStart()
		list.startPeriodicUpdate(REFRESH_INTERVAL_MS, automaticRefresh)
		startRoomObserver()
		startTypingObserver()
	}

	override fun onStop() {
		stopRoomObserver()
		stopTypingObserver()
		list.stopPeriodicUpdate()
		super.onStop()
	}

	override fun onResume() {
		super.onResume()
		composerResumed = true
	}

	override fun onPause() {
		composerResumed = false
		typing?.stop()
		saveDraft()
		super.onPause()
	}

	internal fun restoreDraft(state: Bundle?) {
		if (state == null && ::composeText.isInitialized) {
			val sourceId = intent?.getStringExtra(SOURCE_ID) ?: connector?.source?.id
			val draft = ConnectorDraftStore.getDraft(sourceId, threadId)
			if (!draft.isNullOrEmpty()) {
				composeText.setText(draft)
				composeText.setSelection(composeText.length())
			}
		}
	}

	internal fun saveDraft() {
		if (::composeText.isInitialized && !sendInFlight) {
			val sourceId = intent?.getStringExtra(SOURCE_ID) ?: connector?.source?.id
			ConnectorDraftStore.setDraft(sourceId, threadId, composeText.text?.toString())
		}
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (!hasFocus) typing?.stop()
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.findItem(R.id.action_leave_connector_room)?.apply {
			isVisible = canLeaveRoom()
			isEnabled = !leavePending
		}
		menu.findItem(R.id.action_mark_unread_connector_conversation)?.apply {
			isVisible = canMarkUnread()
			isEnabled = !unreadPending
		}
		menu.findItem(R.id.action_refresh_connector_conversation)?.isVisible =
			shouldShowManualRefreshAction(
				connector?.isEnabled() == true,
				threadId,
				messageState,
			)
		menu.findItem(R.id.action_find_connector_conversation)?.isVisible =
			shouldShowFindAction(connector?.source?.id)
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val itemId = item.itemId
		if (itemId == R.id.action_leave_connector_room) {
			confirmLeaveRoom()
			return true
		}
		if (itemId == R.id.action_mark_unread_connector_conversation) {
			markUnreadAndClose()
			return true
		}
		if (itemId == android.R.id.home) {
			onBackPressed()
			return true
		} else if (isManualRefreshAction(itemId)) {
			retryRefresh()
			return true
		} else if (isFindAction(itemId)) {
			toggleFind()
			return true
		}
		return super.onOptionsItemSelected(item)
	}

	internal fun retryRefresh() {
		retryRequested = true
		retryCoalescer.onUpdate()
	}

	override fun onDestroy() {
		stopRoomObserver()
		stopTypingObserver()
		retryCoalescer.cancel()
		loadFailedSnackbar?.dismiss()
		loadFailedSnackbar = null
		lastLoadFailed = false
		typing?.stop()
		leaveOwner.invalidate()
		leaveDialog?.dismiss()
		unreadOwner.invalidate()
		loadOwner.invalidate()
		retryOwner.invalidate()
		inFlightRetryIds.clear()
		super.onDestroy()
	}

	private fun loadMessages(showLoading: Boolean = true, isInitialOpen: Boolean = false) {
		automaticRefreshRunning = !showLoading
		val loadGeneration = loadOwner.begin()
		val activeConnector = connector
		if (activeConnector == null || !hasValidThreadId(threadId)) {
			showMessages(loadGeneration, ConnectorConversationMessageListState(), LOAD_FAILED)
			return
		}
		if (showLoading && messageState.messages.isEmpty()) {
			submitMessageState(
				messageState.copy(
					emptyText = getString(emptyTextForState(LOADING), sourceName),
					isLoading = true,
				),
			)
		}
		ioExecutor.execute {
			if (!loadOwner.owns(loadGeneration)) {
				roomUpdateCoalescer?.onRefreshCompleted()
				retryCoalescer.onRefreshCompleted()
				return@execute
			}
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
				if (!loadOwner.owns(loadGeneration)) {
					roomUpdateCoalescer?.onRefreshCompleted()
					retryCoalescer.onRefreshCompleted()
					return@execute
				}
				val result = activeConnector.getRecentMessageReadResult(
					threadId,
					MESSAGE_LIMIT,
				)
				when (result) {
					is ConnectorMessageReadResult.Success -> {
						val state = ConnectorConversationMessageListState(
							ConnectorConversationMessageItem.fromMessages(result.messages),
						)
						val topic = try {
							activeConnector.getThreadTopic(threadId)
						} catch (e: RuntimeException) {
							null
						}
						showMessages(loadGeneration, state, EMPTY, topic)
						if (isInitialOpen || result.messages.isNotEmpty()) {
							markThreadRead(loadGeneration)
						}
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

	// Runs on ioExecutor, already inside loadMessages' load block. Re-checks loadOwner so a
	// stale/cancelled load (e.g. the activity moved to a different thread) cannot still mark a
	// room read after this screen no longer owns it.
	private fun markThreadRead(loadGeneration: Long) {
		val marker = readMarker ?: return
		synchronized(readStateLock) {
			if (unreadPending || !loadOwner.owns(loadGeneration)) return
			try {
				marker.markThreadRead(threadId)
			} catch (e: RuntimeException) {
			}
		}
	}

	private fun canLeaveRoom(): Boolean = connector is ConnectorRoomLeaver &&
		connector?.source?.id == ConnectorSources.MATRIX_ID &&
		connector?.isEnabled() == true &&
		connector?.isAuthorized() == true &&
		hasValidThreadId(threadId)

	private fun confirmLeaveRoom() {
		if (leavePending || leaveDialog != null || !canLeaveRoom() || isFinishing || isDestroyed) return
		val active = connector ?: return
		val room = threadId
		val prepared = (active as ConnectorRoomLeaver).prepareLeave(room)
		val generation = leaveOwner.begin()
		fun current() = leaveOwner.owns(generation) &&
			connector === active &&
			threadId == room &&
			!isFinishing &&
			!isDestroyed &&
			active.isAuthorized() &&
			prepared?.isCurrent() != false
		leaveDialog = AlertDialog.Builder(this)
			.setMessage(R.string.connector_leave_room_confirm)
			.setNegativeButton(R.string.cancel, null)
			.setPositiveButton(R.string.connector_leave_room_action) { _, _ ->
				if (!leavePending && current()) {
					leavePending = true
					invalidateOptionsMenu()
					ioExecutor.execute {
						if (!current()) return@execute
						val success = try {
							prepared?.leave() == true
						} catch (e: RuntimeException) {
							false
						}
						runOnUiThread {
							if (!current()) return@runOnUiThread
							leavePending = false
							invalidateOptionsMenu()
							if (success) {
								ConnectorDraftStore.clearDraft(
									intent?.getStringExtra(SOURCE_ID) ?: connector?.source?.id,
									room,
								)
								// finish() triggers onPause(), whose saveDraft() would otherwise
								// resurrect the just-cleared draft from the still-populated field.
								if (::composeText.isInitialized) composeText.setText("")
								// ContactListFragment.onStart reloads the unified inbox on return.
								finish()
							} else {
								BriarSnackbarBuilder()
									.setAction(R.string.connector_leave_room_retry) { confirmLeaveRoom() }
									.make(list, R.string.connector_leave_room_failed, Snackbar.LENGTH_LONG)
									.show()
							}
						}
					}
				}
			}
			.setOnDismissListener { leaveDialog = null }
			.show()
	}

	private fun canMarkUnread(): Boolean = connector is ConnectorUnreadMarker &&
		connector?.isEnabled() == true &&
		connector?.isAuthorized() == true &&
		hasValidThreadId(threadId)

	private fun markUnreadAndClose() {
		if (unreadPending || !canMarkUnread()) return
		val activeConnector = connector ?: return
		val room = threadId
		val prepared = (activeConnector as ConnectorUnreadMarker).prepareMarkUnread(room)
		val generation = unreadOwner.begin()
		unreadPending = true
		invalidateOptionsMenu()
		ioExecutor.execute {
			// Only background work takes this lock: finish an active read before setting unread.
			val success = synchronized(readStateLock) {
				if (!unreadOwner.owns(generation) ||
					threadId != room ||
					!activeConnector.isAuthorized()
				) {
					return@execute
				}
				try {
					prepared?.markUnread() == true
				} catch (e: RuntimeException) {
					false
				}
			}
			runOnUiThread {
				if (!unreadOwner.owns(generation) ||
					threadId != room ||
					isFinishing ||
					!activeConnector.isAuthorized() ||
					prepared?.isCurrent() == false
				) {
					return@runOnUiThread
				}
				if (success) {
					finish()
				} else {
					unreadPending = false
					invalidateOptionsMenu()
					BriarSnackbarBuilder().make(
						list,
						R.string.connector_mark_unread_failed,
						Snackbar.LENGTH_LONG,
					).show()
				}
			}
		}
	}

	private fun showMessages(
		loadGeneration: Long,
		state: ConnectorConversationMessageListState,
		availabilityState: ConnectorConversationAvailabilityState,
		topic: String? = null,
	) {
		runOnUiThread {
			try {
				if (!loadOwner.owns(loadGeneration)) return@runOnUiThread
				bindTopic(supportActionBar, topic)
				automaticRefreshRunning = false
				val next = if (availabilityState == EMPTY) {
					messageState.reconcile(state.messages).copy(isLoading = false)
				} else {
					state
				}
				val nextState = next.withAvailabilityState(
					availabilityState,
					if (availabilityState == EMPTY) next else messageState,
					getString(emptyTextForState(availabilityState), sourceName),
				)
				submitMessageState(nextState)
				if (availabilityState == LOAD_FAILED) {
					showLoadFailedFeedback(nextState.messages.isEmpty())
				} else {
					lastLoadFailed = false
					retryRequested = false
					loadFailedSnackbar?.dismiss()
					loadFailedSnackbar = null
					list.setEmptyAction("")
				}
			} finally {
				roomUpdateCoalescer?.onRefreshCompleted()
				retryCoalescer.onRefreshCompleted()
			}
		}
	}

	private fun showLoadFailedFeedback(isEmpty: Boolean) {
		if (isEmpty) {
			list.setEmptyAction(R.string.connector_conversation_retry_refresh)
		}
		val text = getString(R.string.connector_conversation_load_failed, sourceName)
		val shouldShowSnackbar = retryRequested || !lastLoadFailed
		lastLoadFailed = true
		retryRequested = false
		if (shouldShowSnackbar) {
			loadFailedSnackbar?.dismiss()
			loadFailedSnackbar = BriarSnackbarBuilder()
				.setAction(R.string.connector_conversation_retry_refresh) { retryRefresh() }
				.make(list, text, Snackbar.LENGTH_LONG)
				.also { it.show() }
		}
	}

	internal fun getLoadFailedSnackbar(): Snackbar? = loadFailedSnackbar

	internal fun startRoomObserver() {
		val observer = connector as? ConnectorRoomObserver ?: return
		if (!hasValidThreadId(threadId)) return
		stopRoomObserver()
		val room = threadId
		val coalescer = ConnectorRoomUpdateCoalescer {
			runOnUiThread {
				if (!isFinishing && !isDestroyed) {
					loadMessages(showLoading = false)
				}
			}
		}
		synchronized(this) { roomUpdateCoalescer = coalescer }
		ioExecutor.execute {
			val sub = observer.observeRoom(room) {
				coalescer.onUpdate()
			}
			synchronized(this) {
				if (roomUpdateCoalescer === coalescer) {
					roomObserverSubscription = sub
				} else {
					sub?.cancel()
				}
			}
		}
	}

	internal fun stopRoomObserver() {
		synchronized(this) {
			roomUpdateCoalescer?.cancel()
			roomUpdateCoalescer = null
			roomObserverSubscription?.cancel()
			roomObserverSubscription = null
		}
	}

	internal fun getTypingIndicator(): TextView = typingIndicator

	internal fun startTypingObserver() {
		val observer = connector as? ConnectorTypingObserver ?: return
		if (!hasValidThreadId(threadId)) return
		stopTypingObserver()
		val room = threadId
		val generation = Any()
		synchronized(this) {
			typingGeneration = generation
		}
		ioExecutor.execute {
			val sub = observer.observeTyping(room) { isTyping ->
				runOnUiThread {
					if (!isFinishing && !isDestroyed) {
						updateTypingIndicator(generation, isTyping)
					}
				}
			}
			synchronized(this) {
				if (typingGeneration === generation && threadId == room) {
					typingObserverSubscription = sub
				} else {
					sub?.cancel()
				}
			}
		}
	}

	internal fun stopTypingObserver() {
		synchronized(this) {
			typingGeneration = null
			typingObserverSubscription?.cancel()
			typingObserverSubscription = null
		}
		clearTypingIndicator()
	}

	internal fun updateTypingIndicator(generation: Any, isTyping: Boolean) {
		val stillObserving = synchronized(this) { typingGeneration === generation }
		if (!stillObserving) return
		if (isFinishing || isDestroyed) {
			clearTypingIndicator()
			return
		}
		typingIndicatorHandler.removeCallbacks(typingExpiryRunnable)
		if (isTyping) {
			typingIndicator.visibility = View.VISIBLE
			typingIndicatorHandler.postDelayed(typingExpiryRunnable, TYPING_EXPIRY_MS)
		} else {
			typingIndicator.visibility = View.GONE
		}
	}

	internal fun clearTypingIndicator() {
		typingIndicatorHandler.removeCallbacks(typingExpiryRunnable)
		if (::typingIndicator.isInitialized) {
			typingIndicator.visibility = View.GONE
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
		if (state.isLoading && state.messages.isEmpty()) list.showProgressBar()
		state.visibleStatusText?.takeIf {
			it != messageState.visibleStatusText &&
				it != getString(R.string.connector_conversation_load_failed, sourceName)
		}?.let {
			BriarSnackbarBuilder()
				.make(list, it, Snackbar.LENGTH_LONG)
				.show()
		}
		messageState = state
		searchState = searchState.withMessages(state.messages)
		updateFindUi()
		invalidateOptionsMenu()
		updateComposeBarVisibility()
	}

	private fun onFindQueryChanged(query: String) {
		searchState = searchState.withQuery(query, messageState.messages)
		updateFindUi()
		scrollToSelectedMatch()
	}

	private fun navigateFind(forward: Boolean) {
		searchState = if (forward) searchState.next() else searchState.previous()
		updateFindUi()
		scrollToSelectedMatch()
	}

	private fun toggleFind() {
		if (findBar.visibility == View.VISIBLE) {
			findBar.visibility = View.GONE
			findQuery.setText("")
			searchState = ConnectorConversationSearchState()
			updateFindUi()
		} else {
			findBar.visibility = View.VISIBLE
			findQuery.requestFocus()
		}
	}

	private fun updateFindUi() {
		findCount.text = when {
			!searchState.isActive -> ""
			searchState.matchCount == 0 -> getString(R.string.connector_conversation_find_no_matches)
			else -> getString(
				R.string.connector_conversation_find_count,
				searchState.selectedIndex + 1,
				searchState.matchCount,
			)
		}
		findPrevious.isEnabled = searchState.matchCount > 0
		findNext.isEnabled = searchState.matchCount > 0
	}

	private fun scrollToSelectedMatch() {
		val id = searchState.selectedId ?: return
		val index = messageState.messages.indexOfFirst { it.stableId == id }
		if (index >= 0) list.scrollToPosition(index)
	}

	private fun updateComposeBarVisibility() {
		val enabled = connector?.isEnabled() == true
		val authorized = connector?.isAuthorized() == true
		fileButton.visibility = if (canSendFile()) View.VISIBLE else View.GONE
		val visible = shouldShowComposeBar(enabled, authorized, threadId, textSender)
		composeBar.visibility = if (visible) View.VISIBLE else View.GONE
		attachButton.visibility =
			if (shouldShowAttachButton(
					enabled,
					authorized,
					threadId,
					imageSender,
				)
			) {
				View.VISIBLE
			} else {
				View.GONE
			}
	}

	private fun onSendClicked() {
		val sender = textSender ?: return
		if (sendInFlight) return
		val text = composeText.text?.toString()?.trim().orEmpty()
		if (text.isEmpty()) return
		sendInFlight = true
		setComposeEnabled(false)
		val sourceId = intent?.getStringExtra(SOURCE_ID) ?: connector?.source?.id
		ConnectorDraftStore.clearDraft(sourceId, threadId)
		submitMessageState(messageState.beginTextSend(connector!!.source, threadId, text))
		val echoId = messageState.localEchoes.last().item.stableId
		val activeThreadId = threadId
		ioExecutor.execute {
			val sent = try {
				sender.sendText(activeThreadId, text)
			} catch (e: RuntimeException) {
				false
			}
			runOnUiThread {
				if (isFinishing || isDestroyed) return@runOnUiThread
				submitMessageState(messageState.completeSend(echoId, sent))
				onSendCompleted(sent, clearComposeText = true)
			}
		}
	}

	private fun canSendFile(): Boolean = fileSender != null &&
		connector?.isEnabled() == true &&
		connector?.isAuthorized() == true &&
		hasValidThreadId(threadId)

	private fun onFileChosen(uri: Uri?) {
		val prepared = fileUpload.choose(uri != null) ?: return
		if (!canSendFile() || uri?.scheme != "content") {
			fileUpload.complete(false)
			return
		}
		submitMessageState(
			messageState.beginMediaSend(connector!!.source, threadId, ConnectorMessageType.FILE),
		)
		pendingFileEchoId = messageState.localEchoes.last().item.stableId
		try {
			ioExecutor.execute {
				val sent = try {
					val mime = contentResolver.getType(uri) ?: "application/octet-stream"
					sendConnectorFile(prepared, mime) { contentResolver.openInputStream(uri) }
				} catch (e: RuntimeException) {
					false
				}
				runOnUiThread { fileUpload.complete(sent) }
			}
		} catch (e: RuntimeException) {
			fileUpload.complete(false)
		}
	}

	private fun onAttachImageClicked() {
		if (!canSendImage() || sendInFlight) return
		launchActivityToOpenFile(this, imageDocumentLauncher, imageContentLauncher, "image/*")
	}

	private fun canSendImage(): Boolean = shouldShowAttachButton(
		connector?.isEnabled() == true,
		connector?.isAuthorized() == true,
		threadId,
		imageSender,
	)

	private fun onImageChosen(uri: Uri?) {
		if (uri == null || sendInFlight) return
		if (!imageUpload.begin(uri.toString())) return
		if (!canSendImage()) {
			imageUpload.complete(false)
			return
		}
		val sender = imageSender ?: return
		sendInFlight = true
		setComposeEnabled(false)
		submitMessageState(
			messageState.beginMediaSend(connector!!.source, threadId, ConnectorMessageType.PHOTO),
		)
		pendingImageEchoId = messageState.localEchoes.last().item.stableId
		val activeThreadId = threadId
		ioExecutor.execute {
			val sent = try {
				sendPickedImage(sender, activeThreadId, uri)
			} catch (e: IOException) {
				false
			} catch (e: RuntimeException) {
				false
			}
			runOnUiThread { imageUpload.complete(sent) }
		}
	}

	private fun sendPickedImage(sender: ConnectorImageSender, threadId: String, uri: Uri): Boolean {
		val mimeType = contentResolver.getType(uri) ?: return false
		val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
		val fileName = "image." + (extension ?: DEFAULT_IMAGE_FILE_EXTENSION)
		return sendConnectorImage(sender, threadId, mimeType, fileName) {
			contentResolver.openInputStream(uri)
		}
	}

	private fun completeImageEcho(sent: Boolean) {
		val id = pendingImageEchoId ?: return
		pendingImageEchoId = null
		submitMessageState(messageState.completeSend(id, sent))
	}

	private fun completeFileEcho(sent: Boolean) {
		val id = pendingFileEchoId ?: return
		pendingFileEchoId = null
		submitMessageState(messageState.completeSend(id, sent))
	}

	private fun onSendCompleted(sent: Boolean, clearComposeText: Boolean) {
		if (isFinishing || isDestroyed) return
		sendInFlight = false
		setComposeEnabled(true)
		if (sent) {
			typing?.stop()
			if (clearComposeText) composeText.setText("")
			if (!clearComposeText) {
				BriarSnackbarBuilder().make(
					composeBar,
					R.string.connector_image_sent,
					Snackbar.LENGTH_SHORT,
				).show()
			}
			loadMessages(showLoading = false)
		} else {
			val snackbar = BriarSnackbarBuilder()
			if (!clearComposeText) {
				snackbar.setAction(R.string.connector_image_retry) {
					onImageChosen(imageUpload.pendingImage?.let(Uri::parse))
				}
			}
			snackbar.make(
				list,
				if (clearComposeText) {
					R.string.connector_conversation_send_failed
				} else {
					R.string.connector_image_failed
				},
				if (clearComposeText) Snackbar.LENGTH_LONG else Snackbar.LENGTH_INDEFINITE,
			).show()
		}
	}

	private fun setComposeEnabled(enabled: Boolean) {
		if (!enabled) typing?.stop()
		composeText.isEnabled = enabled
		sendButton.isEnabled = enabled
		attachButton.isEnabled = enabled
		fileButton.isEnabled = enabled
		sendButton.setText(
			if (enabled) R.string.connector_conversation_send else R.string.connector_message_sending,
		)
	}

	private fun onDeleteMessageClicked(item: ConnectorConversationMessageItem) {
		// MX-020: Prompt to delete an outgoing message
		val deleter = connector as? ConnectorMessageDeleter ?: return
		val currentThreadId = threadId
		if (currentThreadId.isEmpty()) return

		AlertDialog.Builder(this, R.style.BriarDialogTheme)
			.setTitle(R.string.delete)
			.setMessage(R.string.dialog_message_delete)
			.setPositiveButton(R.string.delete) { _, _ ->
				executeDeleteMessage(deleter, currentThreadId, item)
			}
			.setNegativeButton(R.string.cancel, null)
			.show()
	}

	private fun executeDeleteMessage(
		deleter: ConnectorMessageDeleter,
		targetThreadId: String,
		item: ConnectorConversationMessageItem,
	) {
		ioExecutor.execute {
			val success = deleter.deleteMessage(targetThreadId, item.connectorMessageId)
			runOnUiThread {
				if (isFinishing || isDestroyed) return@runOnUiThread
				val outcome = resolveDeleteOutcome(messageState, item, success)
				outcome.feedbackStringRes?.let { Snackbar.make(list, it, Snackbar.LENGTH_LONG).show() }
					?: submitMessageState(outcome.nextState)
			}
		}
	}

	internal fun onRetrySendClicked(item: ConnectorConversationMessageItem) {
		if (item.type != ConnectorMessageType.TEXT ||
			!item.isOutgoing ||
			item.localEchoStatus != ConnectorLocalEchoStatus.FAILED
		) {
			return
		}
		val echoId = item.stableId
		if (inFlightRetryIds.contains(echoId)) return
		val activeConnector = connector ?: return
		val sender = textSender ?: return
		val unavailable = !activeConnector.isEnabled() || !activeConnector.isAuthorized()
		if (unavailable || isFinishing || isDestroyed) return
		val activeThreadId = threadId
		if (activeThreadId.isEmpty()) return

		inFlightRetryIds.add(echoId)
		val generation = retryOwner.begin(echoId)
		submitMessageState(messageState.retrySend(echoId))
		ioExecutor.execute {
			val sent = try {
				if (!activeConnector.isAuthorized()) false else sender.sendText(activeThreadId, item.text)
			} catch (e: RuntimeException) {
				false
			}
			runOnUiThread {
				inFlightRetryIds.remove(echoId)
				val dead = !retryOwner.owns(echoId, generation) || isFinishing || isDestroyed
				if (dead) {
					retryOwner.clear(echoId)
					return@runOnUiThread
				}
				retryOwner.clear(echoId)
				submitMessageState(messageState.completeSend(echoId, sent))
				if (sent) {
					loadMessages(showLoading = false)
				} else {
					val res = R.string.connector_conversation_send_failed
					BriarSnackbarBuilder().make(list, res, Snackbar.LENGTH_LONG).show()
				}
			}
		}
	}
}

internal data class ConnectorDeleteOutcome(
	val nextState: ConnectorConversationMessageListState,
	val feedbackStringRes: Int? = null,
)

internal fun resolveDeleteOutcome(
	state: ConnectorConversationMessageListState,
	item: ConnectorConversationMessageItem,
	success: Boolean,
): ConnectorDeleteOutcome = if (success) {
	val updated = item.copy(type = ConnectorMessageType.DELETED)
	val messages = state.messages.map { if (it.stableId == item.stableId) updated else it }
	ConnectorDeleteOutcome(state.copy(messages = messages))
} else {
	ConnectorDeleteOutcome(state, R.string.connector_conversation_delete_failed)
}
