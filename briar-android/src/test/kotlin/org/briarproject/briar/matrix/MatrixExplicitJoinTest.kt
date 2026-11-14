package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState

class MatrixExplicitJoinTest {
	private val client = ReflectiveMatrixHomeserverDiscoveryClient()

	@After fun reset() {
		client.close()
		FakeMatrixSdkState.reset()
	}

	private fun login() {
		assertEquals(NONE, client.login("https://matrix.example.org", "alice", "password"))
	}

	@Test fun `account session exposes joining only while ready`() {
		val discovery = object : MatrixHomeserverDiscoveryClient {
			override fun discover(serverName: String) =
				DiscoveryResult.Resolved("https://matrix.example.org")
		}
		val session = MatrixAuthSessionImpl(discovery, client)
		assertNull(session.prepareJoinRoom("#disposable:example.org"))
		session.start()
		session.submitHomeserver("example.org")
		session.submitCredentials("alice", "password")
		assertTrue(session.prepareJoinRoom("#disposable:example.org")!!())
		val queued = session.prepareJoinRoom("!other:example.org")!!
		session.logout()
		assertFalse(queued())
		assertNull(session.prepareJoinRoom("#disposable:example.org"))
	}

	@Test fun `alias resolves through SDK and denial retries without exposing server text`() {
		login()
		val join = client.prepareJoinRoom(" #disposable:example.org ")!!
		FakeMatrixSdkState.joinFails = true
		assertFalse(join())
		FakeMatrixSdkState.joinFails = false
		assertTrue(join())
		assertEquals("!resolved:example.org", client.getJoinedRooms(10).single().roomId)
		assertTrue(join())
		assertEquals(2, FakeMatrixSdkState.joinCalls)
	}

	@Test fun `invalid identifiers do not call SDK and queued join cannot cross sessions`() {
		login()
		for (value in listOf("", "room", "https://matrix.to/#/room", "#room", "!a b:server")) {
			assertNull(client.prepareJoinRoom(value))
		}
		val join = client.prepareJoinRoom("!disposable:example.org")!!
		client.logout()
		login()
		assertFalse(join())
		assertEquals(0, FakeMatrixSdkState.joinCalls)
		assertTrue(client.prepareJoinRoom("!disposable:example.org")!!())
	}
}
