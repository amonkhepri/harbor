package org.briarproject.briar.android.contact;

import android.content.res.Resources;

import org.briarproject.briar.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21)
public class TelegramInboxThreadViewHolderTest {

	@Test
	public void testPreviewTextOnlyPrefixesOutgoingMessages() {
		Resources resources = RuntimeEnvironment.getApplication()
				.getResources();
		TelegramInboxThreadItem incoming = new TelegramInboxThreadItem(7L,
				"chat", 42000L, "incoming preview", false, false);
		TelegramInboxThreadItem outgoing = new TelegramInboxThreadItem(8L,
				"chat", 42000L, "outgoing preview", true, false);

		assertEquals("incoming preview",
				TelegramInboxThreadViewHolder.previewText(resources, incoming));
		assertEquals(resources.getString(
						R.string.telegram_thread_preview_outgoing,
						"outgoing preview"),
				TelegramInboxThreadViewHolder.previewText(resources, outgoing));
	}
}
