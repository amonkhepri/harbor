package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConnectorDraftStoreTest {

	@Before
	@After
	fun cleanUp() {
		ConnectorDraftStore.clearAll()
	}

	@Test
	fun storesAndRetrievesRoomQualifiedDraft() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Hello room 1")
		assertEquals(
			"Hello room 1",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)
	}

	@Test
	fun doesNotLeakAcrossRoomsOrSources() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Room 1 draft")
		// Different room in same source
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room2:matrix.org"))
		// Same room id in different source
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.TELEGRAM_ID, "!room1:matrix.org"))
	}

	@Test
	fun updatingDraftOverwritesPreviousValue() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Draft v1")
		assertEquals(
			"Draft v1",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Draft v2")
		assertEquals(
			"Draft v2",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)
	}

	@Test
	fun settingNullOrEmptyClearsDraft() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Draft")
		assertEquals(
			"Draft",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"),
		)
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "")
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))

		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Draft 2")
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", null)
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))
	}

	@Test
	fun clearDraftRemovesSpecificRoom() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Draft 1")
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room2:matrix.org", "Draft 2")
		ConnectorDraftStore.clearDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org")
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))
		assertEquals(
			"Draft 2",
			ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room2:matrix.org"),
		)
	}

	@Test
	fun clearSourceRemovesOnlyTargetSource() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Matrix draft")
		ConnectorDraftStore.setDraft(ConnectorSources.TELEGRAM_ID, "chat1", "Telegram draft")
		ConnectorDraftStore.clearSource(ConnectorSources.MATRIX_ID)
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))
		assertEquals(
			"Telegram draft",
			ConnectorDraftStore.getDraft(ConnectorSources.TELEGRAM_ID, "chat1"),
		)
	}

	@Test
	fun clearAllRemovesEverything() {
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org", "Draft 1")
		ConnectorDraftStore.setDraft(ConnectorSources.TELEGRAM_ID, "chat1", "Draft 2")
		ConnectorDraftStore.clearAll()
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "!room1:matrix.org"))
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.TELEGRAM_ID, "chat1"))
	}

	@Test
	fun invalidKeysAreSafelyIgnored() {
		ConnectorDraftStore.setDraft(null, "!room:matrix.org", "Draft")
		ConnectorDraftStore.setDraft("", "!room:matrix.org", "Draft")
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, null, "Draft")
		ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "", "Draft")

		assertNull(ConnectorDraftStore.getDraft(null, "!room:matrix.org"))
		assertNull(ConnectorDraftStore.getDraft("", "!room:matrix.org"))
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, null))
		assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, ""))

		ConnectorDraftStore.clearDraft(null, null)
		ConnectorDraftStore.clearSource(null)
		ConnectorDraftStore.clearSource("")
	}
}
