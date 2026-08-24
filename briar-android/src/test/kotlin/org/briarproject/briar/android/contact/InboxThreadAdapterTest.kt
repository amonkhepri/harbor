package org.briarproject.briar.android.contact

import org.briarproject.bramble.test.TestUtils.getContact
import org.briarproject.briar.api.client.MessageTracker.GroupCount
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.identity.AuthorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxThreadAdapterTest {

	private val callback = InboxThreadAdapter.InboxThreadCallback()

	@Test
	fun testConnectorRowsUseGenericStableIds() {
		val item = connectorItem()

		assertEquals("messenger:123", item.stableId)
	}

	@Test
	fun testConnectorContentsCompareGenericFields() {
		val item = connectorItem(previewText = "synthetic preview")
		fun assertContentsChanged(changed: FakeConnectorInboxThreadItem) =
			assertEquals(false, callback.areContentsTheSame(item, changed))

		assertEquals(
			true to true,
			callback.areItemsTheSame(item, item.copy()) to
				callback.areContentsTheSame(item, item.copy()),
		)
		assertContentsChanged(item.copy(title = "new"))
		assertContentsChanged(item.copy(previewText = "new"))
		assertContentsChanged(item.copy(previewType = ConnectorMessageType.PHOTO))
		assertContentsChanged(item.copy(isLastMessageOutgoing = true))
		assertContentsChanged(item.copy(isPreviewLoading = true))
		assertContentsChanged(item.copy(latestActivityMillis = 2L))
	}

	@Test
	fun testBriarContentsDetectAuthorInfoStatusChange() {
		val contact = getContact()
		val groupCount = GroupCount(0, 0, 1L)
		fun briarItem(status: AuthorInfo.Status) = BriarInboxThreadItem(
			ContactListItem(contact, AuthorInfo(status), false, groupCount),
		)

		assertEquals(
			true,
			callback.areContentsTheSame(
				briarItem(AuthorInfo.Status.VERIFIED),
				briarItem(AuthorInfo.Status.VERIFIED),
			),
		)
		assertEquals(
			false,
			callback.areContentsTheSame(
				briarItem(AuthorInfo.Status.VERIFIED),
				briarItem(AuthorInfo.Status.UNVERIFIED),
			),
		)
	}

	private fun connectorItem(previewText: String = "") = FakeConnectorInboxThreadItem(
		connectorSource = MESSENGER,
		connectorThreadId = "123",
		title = "synthetic title",
		latestActivityMillis = 1L,
		previewText = previewText,
	)

	private companion object {
		val MESSENGER = ConnectorSource("messenger", "Messenger")
	}
}
