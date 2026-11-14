package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.Membership

class MatrixInvitationDeclineTest {
	private val client = ReflectiveMatrixHomeserverDiscoveryClient()
	private val room = "!invite:example.org"

	@After fun reset() {
		client.close()
		FakeMatrixSdkState.reset()
	}

	private fun login(membership: Membership = Membership.INVITED) {
		assertEquals(NONE, client.login("https://matrix.example.org", "alice", "password"))
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(room, membership = membership))
	}

	@Test fun `failed decline preserves invitation and retry removes it without joining`() {
		login()
		val prepared = client.prepareDeclineInvitation(room)!!
		FakeMatrixSdkState.leaveFails = true
		assertFalse(prepared.decline())
		assertEquals(room, client.getInvitedRooms(10).single().roomId)
		FakeMatrixSdkState.leaveFails = false
		assertTrue(prepared.decline())
		assertTrue(prepared.decline())
		assertTrue(client.getInvitedRooms(10).isEmpty())
		assertTrue(client.getJoinedRooms(10).isEmpty())
		assertEquals(2, FakeMatrixSdkState.leaveCalls)
		assertEquals(0, FakeMatrixSdkState.joinCalls)
	}

	@Test fun `decline cannot leave a joined room or cross sessions`() {
		login(Membership.JOINED)
		assertFalse(client.prepareDeclineInvitation(room)!!.decline())
		val stale = client.prepareDeclineInvitation(room)!!
		client.logout()
		assertNull(client.prepareDeclineInvitation(room))
		login()
		assertFalse(stale.isCurrent())
		assertFalse(stale.decline())
		assertEquals(0, FakeMatrixSdkState.leaveCalls)
		assertEquals(1, client.getInvitedRooms(10).size)
	}
}
