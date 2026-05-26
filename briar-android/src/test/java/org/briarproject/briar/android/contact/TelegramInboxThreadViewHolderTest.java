package org.briarproject.briar.android.contact;

import android.content.res.Resources;
import android.content.res.XmlResourceParser;

import org.briarproject.briar.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21)
public class TelegramInboxThreadViewHolderTest {

	private static final String ANDROID_NS =
			"http://schemas.android.com/apk/res/android";

	@Test
	public void testThreadIconExposesGenericTelegramSourceLabel()
			throws Exception {
		Resources resources = RuntimeEnvironment.getApplication()
				.getResources();
		XmlResourceParser parser =
				resources.getXml(R.layout.list_item_telegram_thread);
		while (parser.next() != XmlPullParser.END_DOCUMENT) {
			if (parser.getEventType() != XmlPullParser.START_TAG) continue;
			if (!"ImageView".equals(parser.getName())) continue;
			if (parser.getAttributeResourceValue(ANDROID_NS, "id", 0) !=
					R.id.telegramThreadIcon) continue;
			assertEquals(R.string.telegram_thread_source_content_description,
					parser.getAttributeResourceValue(ANDROID_NS,
							"contentDescription", 0));
			return;
		}
		fail("telegramThreadIcon not found");
	}

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
