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
import org.briarproject.briar.android.connector.ConnectorConversationAdapter
import org.briarproject.briar.android.connector.ConnectorConversationMessageItem
import org.briarproject.briar.android.connector.ConnectorConversationMessageListState
import org.briarproject.briar.android.connector.connectorConversationAvailabilityState
import org.briarproject.briar.android.connector.getConversationMessageItems
import org.briarproject.briar.android.view.BriarRecyclerView
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

		@JvmStatic
		fun emptyTextForState(
			connectorEnabled: Boolean,
			loadFailed: Boolean,
		): Int = emptyTextForState(connectorEnabled, true, loadFailed)

		@JvmStatic
		fun emptyTextForState(
			connectorEnabled: Boolean,
			authorized: Boolean,
			loadFailed: Boolean,
		): Int = emptyTextForState(
			connectorEnabled,
			authorized,
			loadFailed,
			false
		)

		@JvmStatic
		fun emptyTextForState(
			connectorEnabled: Boolean,
			authorized: Boolean,
			loadFailed: Boolean,
			loadPending: Boolean,
		): Int = when (connectorConversationAvailabilityState(
			connectorEnabled, authorized, loadFailed, loadPending)) {
			ConnectorConversationAvailabilityState.LOADING ->
				R.string.telegram_conversation_loading
			ConnectorConversationAvailabilityState.DISABLED ->
				R.string.telegram_conversation_disabled
			ConnectorConversationAvailabilityState.ACCOUNT_UNAVAILABLE ->
				R.string.telegram_conversation_account_unavailable
			ConnectorConversationAvailabilityState.LOAD_FAILED ->
				R.string.telegram_conversation_load_failed
			ConnectorConversationAvailabilityState.EMPTY ->
				R.string.telegram_conversation_empty
		}

		@JvmStatic
		fun isManualRefreshAction(itemId: Int): Boolean =
			itemId == R.id.action_refresh_telegram_conversation

		@JvmStatic
		fun shouldShowManualRefreshAction(
			connectorEnabled: Boolean,
			chatId: Long,
			messageLoadPending: Boolean,
		): Boolean =
			connectorEnabled && hasValidChatId(chatId) && !messageLoadPending

		@JvmStatic
		fun hasValidChatId(chatId: Long): Boolean = chatId != 0L

		@JvmStatic
		fun titleText(title: String?, fallback: String): String =
			if (title == null || title.trim().isEmpty()) fallback else title
	}

	private val adapter = ConnectorConversationAdapter()

	@Inject
	lateinit var telegramConnector: TelegramConnector

	@Inject
	@field:IoExecutor
	lateinit var ioExecutor: Executor

	private lateinit var list: BriarRecyclerView
	private var chatId = 0L
	private var messageLoadPending = false

	override fun injectActivity(component: ActivityComponent) {
		component.inject(this)
	}

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setContentView(R.layout.activity_telegram_conversation)

		chatId = intent.getLongExtra(CHAT_ID, 0L)
		val title = titleText(
			intent.getStringExtra(CHAT_TITLE),
			getString(R.string.telegram_conversation_title)
		)

		supportActionBar?.apply {
			setDisplayHomeAsUpEnabled(true)
			this.title = title
			setSubtitle(R.string.telegram_origin_label)
		}

		list = findViewById(R.id.connectorConversationList)
		list.setLayoutManager(LinearLayoutManager(this))
		list.setAdapter(adapter)
		list.setEmptyText(R.string.telegram_conversation_empty)
		loadMessages()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.telegram_conversation_actions, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		menu.findItem(R.id.action_refresh_telegram_conversation)?.isVisible =
			shouldShowManualRefreshAction(
				telegramConnector.isEnabled(),
				chatId,
				messageLoadPending
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
			showMessages(emptyList(), emptyTextForState(true, true, true))
			return
		}
		messageLoadPending = true
		invalidateOptionsMenu()
		list.setEmptyText(emptyTextForState(true, true, false, true))
		ioExecutor.execute {
			if (!telegramConnector.isEnabled()) {
				showMessages(emptyList(), emptyTextForState(false, false, false))
				return@execute
			}
			if (!telegramConnector.isAuthorized()) {
				showMessages(emptyList(), emptyTextForState(true, false, false))
				return@execute
			}
			try {
				val messages = telegramConnector.getConversationMessageItems(
					chatId.toString(),
					MESSAGE_LIMIT
				)
				showMessages(messages, emptyTextForState(true, true, false))
			} catch (e: RuntimeException) {
				showMessages(emptyList(), emptyTextForState(true, true, true))
			}
		}
	}

	private fun showMessages(
		messages: List<ConnectorConversationMessageItem>,
		emptyTextRes: Int,
	) {
		runOnUiThread {
			val state = ConnectorConversationMessageListState(messages, getString(emptyTextRes))
			messageLoadPending = false
			list.setEmptyText(state.emptyText)
			adapter.submitList(state.messages)
			invalidateOptionsMenu()
		}
	}
}
