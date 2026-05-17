package org.briarproject.briar.android.contact;

import org.briarproject.briar.api.telegram.TelegramChat;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class InboxThreadMergerTest {

	@Test
	public void testTelegramRowsMapSecondsToMillis() {
		List<InboxThreadItem> items = InboxThreadMerger.merge(
				Collections.emptyList(),
				Collections.singletonList(new TelegramInboxThreadItem(
						new TelegramChat(7L, "chat", 42)
				))
		);

		TelegramInboxThreadItem item =
				(TelegramInboxThreadItem) items.get(0);
		assertEquals(7L, item.getChatId());
		assertEquals("chat", item.getTitle());
		assertEquals(42000L, item.getLatestActivityMillis());
		assertEquals(InboxThreadItem.Source.TELEGRAM, item.getSource());
	}

	@Test
	public void testMixedItemsSortNewestFirst() {
		List<InboxThreadItem> items = new ArrayList<>(Arrays.asList(
				new FakeInboxThreadItem("older", 1L,
						InboxThreadItem.Source.BRIAR),
				new FakeInboxThreadItem("newer", 3L,
						InboxThreadItem.Source.TELEGRAM),
				new FakeInboxThreadItem("middle", 2L,
						InboxThreadItem.Source.BRIAR)
		));

		InboxThreadMerger.sort(items);

		assertEquals("newer", items.get(0).getStableId());
		assertEquals("middle", items.get(1).getStableId());
		assertEquals("older", items.get(2).getStableId());
	}

	private static class FakeInboxThreadItem implements InboxThreadItem {

		private final String stableId;
		private final long latestActivityMillis;
		private final Source source;

		FakeInboxThreadItem(String stableId, long latestActivityMillis,
				Source source) {
			this.stableId = stableId;
			this.latestActivityMillis = latestActivityMillis;
			this.source = source;
		}

		@Override
		public String getStableId() {
			return stableId;
		}

		@Override
		public long getLatestActivityMillis() {
			return latestActivityMillis;
		}

		@Override
		public Source getSource() {
			return source;
		}
	}
}
