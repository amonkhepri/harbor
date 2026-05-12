package org.briarproject.briar.android.telegram;

import org.briarproject.briar.api.telegram.TelegramMessageIngestSnapshot;
import org.briarproject.briar.api.telegram.TelegramMessageIngestStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TelegramMessageIngestDiagnosticActivityTest {

	@Test
	public void testFormatSnapshotReportsCountsOnly() {
		TelegramMessageIngestSnapshot snapshot =
				new TelegramMessageIngestSnapshot(
						TelegramMessageIngestStatus.MESSAGE_COUNT_AVAILABLE,
						2,
						3
				);

		assertEquals(
				"Telegram ingest diagnostic\n" +
						"status=MESSAGE_COUNT_AVAILABLE\n" +
						"recentChatCount=2\n" +
						"sampledMessageCount=3",
				TelegramMessageIngestDiagnosticActivity.formatSnapshot(snapshot)
		);
	}
}
