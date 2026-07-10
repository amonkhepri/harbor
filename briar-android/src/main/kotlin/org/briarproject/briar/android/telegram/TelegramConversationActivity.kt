package org.briarproject.briar.android.telegram

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.recyclerview.widget.LinearLayoutManager
import org.briarproject.bramble.api.lifecycle.IoExecutor
import org.briarproject.briar.R
import org.briarproject.briar.android.activity.ActivityComponent
import org.briarproject.briar.android.activity.BriarActivity
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.ACCOUNT_UNAVAILABLE
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.DISABLED
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.EMPTY
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.LOAD_FAILED
import org.briarproject.briar.android.connector.ConnectorConversationAvailabilityState.LOADING
import org.briarproject.briar.android.connector.ConnectorConversationAdapter
import org.briarproject.briar.android.connector.ConnectorConversationMessageListState
import org.briarproject.briar.android.connector.getConversationMessageListState
import org.briarproject.briar.android.connector.withAvailabilityState
import org.briarproject.briar.android.view.BriarRecyclerView
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.briarproject.briar.api.telegram.TelegramConnector
import org.briarproject.nullsafety.MethodsNotNullByDefault
import org.briarproject.nullsafety.ParametersNotNullByDefault
import java.util.concurrent.Executor
import javax.inject.Inject

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class TelegramConversationActivity : BriarActivity() {

	companion object {
		const val CHAT_ID = "telegram.CHAT_ID"
		const val CHAT_TITLE = "telegram.CHAT_TITLE"

		private const val MESSAGE_LIMIT = 50

		private fun emptyTextForState(state: ConnectorConversationAvailabilityState): Int = when (state) {
			LOADING ->
				R.string.telegram_conversation_loading
			DISABLED ->
				R.string.telegram_conversation_disabled
			ACCOUNT_UNAVAILABLE ->
				R.string.telegram_conversation_account_unavailable
			LOAD_FAILED ->
				R.string.telegram_conversation_load_failed
			EMPTY ->
				R.string.telegram_conversation_empty
		}

		private fun isManualRefreshAction(itemId: Int): Boolean =
			itemId == R.id.action_refresh_connector_conversation

		private fun shouldShowManualRefreshAction(
			connectorEnabled: Boolean,
			chatId: Long,
			state: ConnectorConversationMessageListState,
		): Boolean = connectorEnabled && hasValidChatId(chatId) && !state.isLoading

		private fun hasValidChatId(chatId: Long): Boolean = chatId != 0L

		private fun titleText(title: String?, fallback: String): String =
			if (title == null || title.trim().isEmpty()) fallback else title
	}

	private val adapter = ConnectorConversationAdapter()

	@Inject
	lateinit var telegramConnector: TelegramConnector

	private val readOnlyConnector: ReadOnlyConnector
		get() = telegramConnector

	@Inject
	@field:IoExecutor
	lateinit var ioExecutor: Executor

	private lateinit var list: BriarRecyclerView
	private var chatId = 0L
	private var messageState = ConnectorConversationMessageListState()

	override fun injectActivity(component: ActivityComponent) {
		component.inject(this)
	}

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setContentView(R.layout.activity_connector_conversation)

		chatId = intent.getLongExtra(CHAT_ID, 0L)
		val title = titleText(
			intent.getStringExtra(CHAT_TITLE),
			getString(R.string.telegram_conversation_title),
		)

		supportActionBar?.apply {
			setDisplayHomeAsUpEnabled(true)
			this.title = title
			setSubtitle(R.string.telegram_origin_label)
		}

		list = findViewById(R.id.connectorConversationList)
		list.setLayoutManager(LinearLayoutManager(this).apply { stackFromEnd = true })
		list.setAdapter(adapter)
		loadMessages()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.connector_conversation_actions, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.findItem(R.id.action_refresh_connector_conversation)?.isVisible =
			shouldShowManualRefreshAction(
				readOnlyConnector.isEnabled(),
				chatId,
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

	private fun loadMessages() {
		if (!hasValidChatId(chatId)) {
			showMessages(ConnectorConversationMessageListState(), LOAD_FAILED)
			return
		}
		submitMessageState(
			ConnectorConversationMessageListState(
				messageState.messages,
				getString(emptyTextForState(LOADING)),
				isLoading = true,
			),
		)
		ioExecutor.execute {
			if (!readOnlyConnector.isEnabled()) {
				showMessages(ConnectorConversationMessageListState(), DISABLED)
				return@execute
			}
			if (!readOnlyConnector.isAuthorized()) {
				showMessages(ConnectorConversationMessageListState(), ACCOUNT_UNAVAILABLE)
				return@execute
			}
			try {
				val state = readOnlyConnector.getConversationMessageListState(
					chatId.toString(),
					MESSAGE_LIMIT,
				)
				showMessages(state)
			} catch (e: RuntimeException) {
				showMessages(ConnectorConversationMessageListState(), LOAD_FAILED)
			}
		}
	}

	private fun showMessages(
		state: ConnectorConversationMessageListState,
		availabilityState: ConnectorConversationAvailabilityState = EMPTY,
	) {
		runOnUiThread {
			submitMessageState(
				state.withAvailabilityState(
					availabilityState,
					messageState,
					getString(emptyTextForState(availabilityState)),
				),
			)
		}
	}

	private fun submitMessageState(state: ConnectorConversationMessageListState) {
		messageState = state
		list.setEmptyText(state.emptyText)
		adapter.submitState(state)
		invalidateOptionsMenu()
	}
}
