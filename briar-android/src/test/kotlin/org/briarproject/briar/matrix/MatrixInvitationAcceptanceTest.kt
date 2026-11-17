package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.Membership
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class MatrixInvitationAcceptanceTest {
	private val client = ReflectiveMatrixHomeserverDiscoveryClient()
	private val room = "!invite:example.org"

	@After fun reset() {
		client.close()
		FakeMatrixSdkState.reset()
	}

	private fun login() {
		assertEquals(NONE, client.login("https://matrix.example.org", "alice", "password"))
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(room, membership = Membership.INVITED))
	}

	@Test fun `failure retries then success refreshes rooms and permits timeline reads`() {
		login()
		val prepared = client.prepareAcceptInvitation(room)!!
		FakeMatrixSdkState.joinFails = true
		assertFalse(prepared.accept())
		assertEquals(1, client.getInvitedRooms(10).size)
		assertTrue(client.getJoinedRooms(10).isEmpty())
		FakeMatrixSdkState.joinFails = false
		assertTrue(prepared.accept())
		assertEquals(room, client.getJoinedRooms(10).single().roomId)
		assertTrue(client.getInvitedRooms(10).isEmpty())
		assertNotNull(client.getRecentMessages(room, 10))
		assertTrue(prepared.accept())
		assertTrue(client.prepareAcceptInvitation(room)!!.accept())
		assertEquals(2, FakeMatrixSdkState.joinCalls)
	}

	@Test fun `queued acceptance cannot cross logout or replacement session`() {
		login()
		val prepared = client.prepareAcceptInvitation(room)!!
		client.logout()
		assertFalse(prepared.accept())
		assertNull(client.prepareAcceptInvitation(room))
		login()
		assertFalse(prepared.isCurrent())
		assertFalse(prepared.accept())
		assertFalse(client.prepareAcceptInvitation("!missing:example.org")!!.accept())
		assertEquals(0, FakeMatrixSdkState.joinCalls)
	}

	@Test fun `concurrent repeat acceptance joins once`() {
		login()
		val first = client.prepareAcceptInvitation(room)!!
		val repeat = client.prepareAcceptInvitation(room)!!
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		FakeMatrixSdkState.onJoin = {
			entered.countDown()
			check(release.await(5, SECONDS))
		}
		val pool = Executors.newFixedThreadPool(3)
		try {
			val accepted = pool.submit<Boolean> { first.accept() }
			assertTrue(entered.await(3, SECONDS))
			val repeated = pool.submit<Boolean> { repeat.accept() }
			release.countDown()
			assertTrue(accepted.get(3, SECONDS))
			assertTrue(repeated.get(3, SECONDS))
			assertEquals(1, FakeMatrixSdkState.joinCalls)
			pool.submit { client.logout() }.get(3, SECONDS)
			assertFalse(first.isCurrent())
		} finally {
			release.countDown()
			pool.shutdownNow()
		}
	}
}
