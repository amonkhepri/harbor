package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixRoomConnectorTest {

	@Test
	fun `enabled and reports authorized only when the auth session is ready`() {
		val authSession = FakeMatrixAuthSession(MatrixAuthState.CREDENTIAL_ENTRY)
		val connector = MatrixRoomConnector(FakeRoomSnapshotClient(emptyList()), authSession)

		assertTrue(connector.isEnabled())
		assertFalse(connector.isAuthorized())

		authSession.state = MatrixAuthState.READY

		assertTrue(connector.isAuthorized())
	}

	@Test
	fun `getRecentThreads maps snapshots to connector-neutral threads with no known latest message`() {
		val roomSnapshotClient = FakeRoomSnapshotClient(
			listOf(MatrixRoomSnapshot("!a:example.org", "Alpha")),
		)
		val connector =
			MatrixRoomConnector(roomSnapshotClient, FakeMatrixAuthSession(MatrixAuthState.READY))

		val threads = connector.getRecentThreads(10)

		assertEquals(1, threads.size)
		val thread = threads.single()
		assertEquals(ConnectorSources.MATRIX, thread.source)
		assertEquals("!a:example.org", thread.threadId)
		assertEquals("Alpha", thread.title)
		assertEquals("", thread.latestMessageText)
		assertFalse(thread.isLatestMessageOutgoing)
		assertEquals(10, roomSnapshotClient.lastRequestedLimit)
	}

	@Test
	fun `getRecentThreads fails closed without querying rooms when not authorized`() {
		val roomSnapshotClient = FakeRoomSnapshotClient(
			listOf(MatrixRoomSnapshot("!a:example.org", "Alpha")),
		)
		val connector = MatrixRoomConnector(
			roomSnapshotClient,
			FakeMatrixAuthSession(MatrixAuthState.HOMESERVER_ENTRY),
		)

		val threads = connector.getRecentThreads(10)

		assertEquals(emptyList<Any>(), threads)
		assertEquals(null, roomSnapshotClient.lastRequestedLimit)
	}

	@Test
	fun `getRecentMessageReadResult reports no messages, timeline loading is a later slice`() {
		val connector =
			MatrixRoomConnector(
				FakeRoomSnapshotClient(emptyList()),
				FakeMatrixAuthSession(MatrixAuthState.READY),
			)

		val result = connector.getRecentMessageReadResult("!a:example.org", 10)

		assertEquals(ConnectorMessageReadResult.Success(emptyList()), result)
	}

	private class FakeRoomSnapshotClient(private val snapshots: List<MatrixRoomSnapshot>) :
		MatrixRoomSnapshotClient {
		var lastRequestedLimit: Int? = null
			private set

		override fun getJoinedRooms(limit: Int): List<MatrixRoomSnapshot> {
			lastRequestedLimit = limit
			return snapshots
		}
	}

	private class FakeMatrixAuthSession(var state: MatrixAuthState) : MatrixAuthSession {
		override fun getSnapshot(): MatrixAuthSession.Snapshot = MatrixAuthSession.Snapshot(state, NONE)
		override fun start() {}
		override fun submitHomeserver(homeserverUrl: String) {}
		override fun submitCredentials(username: String, password: String) {}
		override fun logout() {}
		override fun close() {}
	}
}
