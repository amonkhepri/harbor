package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.ReceiptType

class ReflectiveMatrixHomeserverDiscoveryClientMarkReadTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test fun leaveClosesHandleRefreshesRoomsAndFencesOldSession() {
		val client = loggedInClient()
		val room = "!room:example.org"
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(room))
		val prepared = client.prepareLeave(room)!!
		FakeMatrixSdkState.leaveFails = true
		assertFalse(prepared.leave())
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(1, client.getJoinedRooms(10).size)
		FakeMatrixSdkState.leaveFails = false
		assertTrue(prepared.leave())
		assertTrue(client.getJoinedRooms(10).isEmpty())
		client.logout()
		assertFalse(prepared.isCurrent())
		assertFalse(prepared.leave())
		assertEquals(2, FakeMatrixSdkState.leaveCalls)
		assertEquals(null, client.prepareLeave(room))
		assertEquals(NONE, client.login("https://matrix.example.org", "alice", "swordfish"))
		assertFalse(prepared.isCurrent())
		assertFalse(prepared.leave())
		assertFalse(client.prepareLeave("!missing:example.org")!!.leave())
		assertEquals(2, FakeMatrixSdkState.leaveCalls)
	}

	@Test
	fun `markRead returns false when no session is retained`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()

		assertFalse(client.markRead("!room:example.org"))
		assertEquals(0, FakeMatrixSdkState.markAsReadCallCount)
	}

	@Test
	fun `markRead returns false when the room cannot be found`() {
		val client = loggedInClient()

		assertFalse(client.markRead("!missing:example.org"))
		assertEquals(0, FakeMatrixSdkState.markAsReadCallCount)
	}

	@Test
	fun `markRead sends a private READ receipt and releases the room`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))

		val marked = client.markRead("!room:example.org")

		assertTrue(marked)
		assertEquals(1, FakeMatrixSdkState.markAsReadCallCount)
		assertEquals("!room:example.org", FakeMatrixSdkState.lastMarkAsReadRoomId)
		assertEquals(ReceiptType.READ_PRIVATE, FakeMatrixSdkState.lastMarkAsReadReceiptType)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
	}

	@Test
	fun `markRead returns false and still releases the room when the SDK call throws`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.markAsReadFailureToThrow = ClientException.Generic("failed", "failed")

		val marked = client.markRead("!room:example.org")

		assertFalse(marked)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
	}

	@Test
	fun `markRead success does not alter room unread count until next successful snapshot`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(
			FakeRoomSpec("!room:example.org", unreadMessagesCount = 3uL),
		)

		val initial = client.getJoinedRooms(10).single()
		assertEquals(3, initial.unreadCount)

		val marked = client.markRead("!room:example.org")
		assertTrue(marked)

		val afterMark = client.getJoinedRooms(10).single()
		assertEquals(3, afterMark.unreadCount)

		FakeMatrixSdkState.roomsToReturn = listOf(
			FakeRoomSpec("!room:example.org", unreadMessagesCount = 0uL),
		)
		val nextSnapshot = client.getJoinedRooms(10).single()
		assertEquals(0, nextSnapshot.unreadCount)
	}

	@Test
	fun `markRead failure does not alter room unread count until next successful snapshot`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(
			FakeRoomSpec("!room:example.org", unreadMessagesCount = 3uL),
		)
		FakeMatrixSdkState.markAsReadFailureToThrow = ClientException.Generic("failed", "failed")

		val initial = client.getJoinedRooms(10).single()
		assertEquals(3, initial.unreadCount)

		val marked = client.markRead("!room:example.org")
		assertFalse(marked)

		val afterFailedMark = client.getJoinedRooms(10).single()
		assertEquals(3, afterFailedMark.unreadCount)

		FakeMatrixSdkState.roomsToReturn = listOf(
			FakeRoomSpec("!room:example.org", unreadMessagesCount = 1uL),
		)
		val nextSnapshot = client.getJoinedRooms(10).single()
		assertEquals(1, nextSnapshot.unreadCount)
	}

	private fun loggedInClient(): ReflectiveMatrixHomeserverDiscoveryClient =
		ReflectiveMatrixHomeserverDiscoveryClient().also { client ->
			assertEquals(NONE, client.login("https://matrix.example.org", "alice", "swordfish"))
		}

	@Test
	fun manualFlagSurvivesFailedReadAndClearsOnlyAfterSuccessfulRead() {
		val client = loggedInClient()
		val room = "!room:example.org"
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(room, unreadMessagesCount = 0uL))
		val prepared = client.prepareMarkUnread(room)!!
		assertTrue(prepared.markUnread())
		assertEquals(true, client.getJoinedRooms(1).single().isMarkedUnread)
		FakeMatrixSdkState.markAsReadFailureToThrow = ClientException.Generic("failed", "failed")
		assertFalse(client.markRead(room))
		assertEquals(true, client.getJoinedRooms(1).single().isMarkedUnread)
		FakeMatrixSdkState.markAsReadFailureToThrow = null
		assertTrue(client.markRead(room))
		assertEquals(false, client.getJoinedRooms(1).single().isMarkedUnread)
		client.logout()
		assertFalse(prepared.isCurrent())
		assertFalse(prepared.markUnread())
	}
}
