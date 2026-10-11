package org.briarproject.briar.android.connector

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.connector.ConnectorTextSender
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.briarproject.nullsafety.MethodsNotNullByDefault
import org.briarproject.nullsafety.ParametersNotNullByDefault
import java.io.IOException
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
		private const val STATE_LAYOUT_MANAGER = "layoutManager"
		private const val DEFAULT_IMAGE_FILE_EXTENSION = "jpg"
		private const val STATE_PENDING_IMAGE = "pendingImage"

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

		private fun shouldShowAttachButton(
			connectorEnabled: Boolean,
			authorized: Boolean,
			threadId: String,
			imageSender: ConnectorImageSender?,
		): Boolean = imageSender != null && connectorEnabled && authorized && hasValidThreadId(threadId)

		private fun titleText(title: String?, fallback: String): String =
			if (title == null || title.trim().isEmpty()) fallback else title
	}

	private val adapter = ConnectorConversationAdapter(::onDeleteMessageClicked)

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
	private lateinit var fileButton: ImageButton
	private lateinit var fileUpload: ConnectorFileUploadState
	private val fileLauncher =
		registerForActivityResult(ActivityResultContracts.GetContent(), ::onFileChosen)
	private lateinit var sourceName: String
	private var threadId = ""
	private lateinit var composeBar: View
	private lateinit var composeText: EditText
	private lateinit var sendButton: Button
	private lateinit var attachButton: ImageButton
	private var sendInFlight = false
	private lateinit var imageUpload: ConnectorImageUploadState

	// Not restored across recreation: matches MX-015's local-only echo scope boundary.
	private var pendingImageEchoId: String? = null
	private var pendingFileEchoId: String? = null

	private val imageContentLauncher = registerForActivityResult(GetImageAdvanced(), ::onImageChosen)
	private val imageDocumentLauncher =
		registerForActivityResult(OpenImageDocumentAdvanced(), ::onImageChosen)
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
			subtitle = sourceName
		}

		list = findViewById(R.id.connectorConversationList)
		layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
		list.setLayoutManager(layoutManager)
		list.setAdapter(adapter)

		composeBar = findViewById(R.id.connectorConversationComposeBar)
		composeText = findViewById(R.id.connectorConversationComposeText)
		composeText.hint = getString(R.string.connector_conversation_compose_hint, sourceName)
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

	private fun loadMessages(showLoading: Boolean = true, isInitialOpen: Boolean = false) {
		automaticRefreshRunning = !showLoading
		val loadGeneration = loadOwner.begin()
		val activeConnector = connector
		if (activeConnector == null || !hasValidThreadId(threadId)) {
			showMessages(loadGeneration, ConnectorConversationMessageListState(), LOAD_FAILED)
			return
		}
		if (showLoading) {
			submitMessageState(
				messageState.copy(
					emptyText = getString(emptyTextForState(LOADING), sourceName),
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
		if (!loadOwner.owns(loadGeneration)) return
		try {
			marker.markThreadRead(threadId)
		} catch (e: RuntimeException) {
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
			val next = if (availabilityState == EMPTY) {
				messageState.reconcile(state.messages).copy(isLoading = false)
			} else {
				state
			}
			submitMessageState(
				next.withAvailabilityState(
					availabilityState,
					if (availabilityState == EMPTY) next else messageState,
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
			val outcome = resolveDeleteOutcome(messageState, item, success)
			runOnUiThread {
				if (isFinishing || isDestroyed) return@runOnUiThread
				outcome.feedbackStringRes?.let { Snackbar.make(list, it, Snackbar.LENGTH_LONG).show() }
					?: submitMessageState(outcome.nextState)
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
