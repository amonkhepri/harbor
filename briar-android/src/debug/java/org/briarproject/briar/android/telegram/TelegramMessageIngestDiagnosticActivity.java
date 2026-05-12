package org.briarproject.briar.android.telegram;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import org.briarproject.briar.android.BriarApplication;
import org.briarproject.briar.api.telegram.TelegramMessageIngestSnapshot;
import org.briarproject.briar.telegram.TelegramMessageIngestDiagnostics;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TelegramMessageIngestDiagnosticActivity extends Activity {

	static final String DIAGNOSTIC_RUNNING =
			"Telegram ingest diagnostic\nstatus=RUNNING";
	private static final String DIAGNOSTIC_FAILED =
			"Telegram ingest diagnostic\nstatus=ERROR";
	private static final int CHAT_LIMIT = 5;
	private static final int MESSAGE_LIMIT = 5;

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		TextView status = new TextView(this);
		status.setText(DIAGNOSTIC_RUNNING);
		setContentView(status);
		executor.execute(() -> {
			String result = readSnapshot();
			runOnUiThread(() -> status.setText(result));
		});
	}

	@Override
	protected void onDestroy() {
		executor.shutdownNow();
		super.onDestroy();
	}

	private String readSnapshot() {
		try {
			BriarApplication app = (BriarApplication) getApplication();
			TelegramMessageIngestSnapshot snapshot =
					new TelegramMessageIngestDiagnostics(
							app.getApplicationComponent().telegramConnector()
					).readSnapshot(CHAT_LIMIT, MESSAGE_LIMIT);
			return formatSnapshot(snapshot);
		} catch (RuntimeException e) {
			return DIAGNOSTIC_FAILED;
		}
	}

	static String formatSnapshot(TelegramMessageIngestSnapshot snapshot) {
		return "Telegram ingest diagnostic\n" +
				"status=" + snapshot.getStatus() + "\n" +
				"recentChatCount=" + snapshot.getRecentChatCount() + "\n" +
				"sampledMessageCount=" + snapshot.getSampledMessageCount();
	}
}
