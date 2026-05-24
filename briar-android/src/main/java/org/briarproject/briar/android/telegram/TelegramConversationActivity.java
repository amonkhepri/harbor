package org.briarproject.briar.android.telegram;

import android.os.Bundle;
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

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_telegram_conversation);

		chatId = getIntent().getLongExtra(CHAT_ID, 0L);
		String title = getIntent().getStringExtra(CHAT_TITLE);
		if (title == null) title = getString(R.string.telegram_origin_label);

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
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void loadMessages() {
		ioExecutor.execute(() -> {
			if (!telegramConnector.isEnabled()) {
				runOnUiThread(() -> adapter.submitList(Collections.emptyList()));
				return;
			}
			try {
				List<TelegramConversationUiMessage> messages =
						TelegramConversationMapper.toUiMessages(
								telegramConnector.getRecentMessages(chatId,
										MESSAGE_LIMIT)
						);
				runOnUiThread(() -> adapter.submitList(messages));
			} catch (RuntimeException e) {
				runOnUiThread(
						() -> adapter.submitList(Collections.emptyList()));
			}
		});
	}
}
