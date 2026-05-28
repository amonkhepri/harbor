package org.briarproject.briar.android.telegram;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.api.telegram.TelegramConnector;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.appcompat.app.ActionBar;
import androidx.recyclerview.widget.LinearLayoutManager;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TelegramConversationActivity extends BriarActivity {

	public static final String CHAT_ID = "telegram.CHAT_ID";
	public static final String CHAT_TITLE = "telegram.CHAT_TITLE";

	private static final int MESSAGE_LIMIT = 50;

	private final TelegramConversationAdapter adapter =
			new TelegramConversationAdapter();

	@Inject
	TelegramConnector telegramConnector;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private BriarRecyclerView list;
	private long chatId;
	private boolean messageLoadPending;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_telegram_conversation);

		chatId = getIntent().getLongExtra(CHAT_ID, 0L);
		String title = titleText(getIntent().getStringExtra(CHAT_TITLE),
				getString(R.string.telegram_conversation_title));

		ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
			ab.setTitle(title);
			ab.setSubtitle(R.string.telegram_origin_label);
		}

		list = findViewById(R.id.telegramConversationList);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		list.setEmptyText(R.string.telegram_conversation_empty);
		loadMessages();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.telegram_conversation_actions, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem refresh = menu.findItem(
				R.id.action_refresh_telegram_conversation);
		if (refresh != null) {
			refresh.setVisible(shouldShowManualRefreshAction(
					telegramConnector.isEnabled(), chatId, messageLoadPending));
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (isManualRefreshAction(itemId)) {
			loadMessages();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void loadMessages() {
		if (!hasValidChatId(chatId)) {
			showMessages(Collections.emptyList(),
					emptyTextForState(true, true, true));
			return;
		}
		messageLoadPending = true;
		invalidateOptionsMenu();
		list.setEmptyText(emptyTextForState(true, true, false, true));
		ioExecutor.execute(() -> {
			if (!telegramConnector.isEnabled()) {
				showMessages(Collections.emptyList(),
						emptyTextForState(false, false, false));
				return;
			}
			if (!telegramConnector.isAuthorized()) {
				showMessages(Collections.emptyList(),
						emptyTextForState(true, false, false));
				return;
			}
			try {
				List<TelegramConversationUiMessage> messages =
						TelegramConversationMapper.toUiMessages(
								telegramConnector.getRecentMessages(chatId,
										MESSAGE_LIMIT)
						);
				showMessages(messages, emptyTextForState(true, true, false));
			} catch (RuntimeException e) {
				showMessages(Collections.emptyList(),
						emptyTextForState(true, true, true));
			}
		});
	}

	private void showMessages(List<TelegramConversationUiMessage> messages,
			int emptyTextRes) {
		runOnUiThread(() -> {
			messageLoadPending = false;
			list.setEmptyText(emptyTextRes);
			adapter.submitList(messages);
			invalidateOptionsMenu();
		});
	}

	static int emptyTextForState(boolean connectorEnabled, boolean loadFailed) {
		return emptyTextForState(connectorEnabled, true, loadFailed);
	}

	static int emptyTextForState(boolean connectorEnabled, boolean authorized,
			boolean loadFailed) {
		return emptyTextForState(connectorEnabled, authorized, loadFailed,
				false);
	}

	static int emptyTextForState(boolean connectorEnabled, boolean authorized,
			boolean loadFailed, boolean loadPending) {
		if (loadPending) return R.string.telegram_conversation_loading;
		if (!connectorEnabled) return R.string.telegram_conversation_disabled;
		if (!authorized) return R.string.telegram_conversation_account_unavailable;
		if (loadFailed) return R.string.telegram_conversation_load_failed;
		return R.string.telegram_conversation_empty;
	}

	static boolean isManualRefreshAction(int itemId) {
		return itemId == R.id.action_refresh_telegram_conversation;
	}

	static boolean shouldShowManualRefreshAction(boolean connectorEnabled,
			long chatId, boolean messageLoadPending) {
		return connectorEnabled && hasValidChatId(chatId) &&
				!messageLoadPending;
	}

	static boolean hasValidChatId(long chatId) {
		return chatId != 0L;
	}

	static String titleText(@Nullable String title, String fallback) {
		if (title == null || title.trim().isEmpty()) return fallback;
		return title;
	}
}
