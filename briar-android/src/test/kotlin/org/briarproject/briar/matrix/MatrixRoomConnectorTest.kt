package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorMessage
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.LoadFailed
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.Success
import org.briarproject.briar.api.connector.ConnectorMessageType
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
		val connector = connector(authSession = authSession)

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
		val connector = connector(
			roomSnapshotClient = roomSnapshotClient,
			authSession = FakeMatrixAuthSession(MatrixAuthState.READY),
		)

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
		val connector = connector(
			roomSnapshotClient = roomSnapshotClient,
			authSession = FakeMatrixAuthSession(MatrixAuthState.HOMESERVER_ENTRY),
		)

		val threads = connector.getRecentThreads(10)

		assertEquals(emptyList<Any>(), threads)
		assertEquals(null, roomSnapshotClient.lastRequestedLimit)
	}

	@Test
	fun `getRecentMessageReadResult maps snapshots to connector-neutral messages`() {
		val messageSnapshotClient = FakeMessageSnapshotClient(
			listOf(
				MatrixMessageSnapshot(
					eventId = "\$a:example.org",
					senderId = "@alice:example.org",
					bodyText = "hello",
					originServerTimestampSeconds = 42L,
					isOutgoing = true,
				),
			),
		)
		val connector = connector(
			messageSnapshotClient = messageSnapshotClient,
			authSession = FakeMatrixAuthSession(MatrixAuthState.READY),
		)

		val result = connector.getRecentMessageReadResult("!a:example.org", 10)

		assertEquals(
			Success(
				listOf(
					ConnectorMessage(
						source = ConnectorSources.MATRIX,
						threadId = "!a:example.org",
						messageId = "\$a:example.org",
						dateSeconds = 42,
						isOutgoing = true,
						text = "hello",
						sourceMessageOrder = 42L,
						type = ConnectorMessageType.TEXT,
					),
				),
			),
			result,
		)
		assertEquals("!a:example.org", messageSnapshotClient.lastRequestedRoomId)
		assertEquals(10, messageSnapshotClient.lastRequestedLimit)
	}

	@Test
	fun `getRecentMessageReadResult reports load failed when the message client returns null`() {
		val connector = connector(
			messageSnapshotClient = FakeMessageSnapshotClient(null),
			authSession = FakeMatrixAuthSession(MatrixAuthState.READY),
		)

		val result = connector.getRecentMessageReadResult("!a:example.org", 10)

		assertEquals(LoadFailed, result)
	}

	@Test
	fun `getRecentMessageReadResult succeeds with an empty list when the room has no messages`() {
		val connector = connector(
			messageSnapshotClient = FakeMessageSnapshotClient(emptyList()),
			authSession = FakeMatrixAuthSession(MatrixAuthState.READY),
		)

		val result = connector.getRecentMessageReadResult("!a:example.org", 10)

		assertEquals(Success(emptyList()), result)
	}

	@Test
	fun `getRecentMessageReadResult fails closed without querying messages when not authorized`() {
		val messageSnapshotClient = FakeMessageSnapshotClient(emptyList())
		val connector = connector(
			messageSnapshotClient = messageSnapshotClient,
			authSession = FakeMatrixAuthSession(MatrixAuthState.HOMESERVER_ENTRY),
		)

		val result = connector.getRecentMessageReadResult("!a:example.org", 10)

		assertEquals(LoadFailed, result)
		assertEquals(null, messageSnapshotClient.lastRequestedRoomId)
	}

	private fun connector(
		roomSnapshotClient: MatrixRoomSnapshotClient = FakeRoomSnapshotClient(emptyList()),
		messageSnapshotClient: MatrixMessageSnapshotClient = FakeMessageSnapshotClient(emptyList()),
		authSession: MatrixAuthSession,
	): MatrixRoomConnector =
		MatrixRoomConnector(roomSnapshotClient, authSession, messageSnapshotClient)

	private class FakeRoomSnapshotClient(private val snapshots: List<MatrixRoomSnapshot>) :
		MatrixRoomSnapshotClient {
		var lastRequestedLimit: Int? = null
			private set

		override fun getJoinedRooms(limit: Int): List<MatrixRoomSnapshot> {
			lastRequestedLimit = limit
			return snapshots
		}
	}

	private class FakeMessageSnapshotClient(private val snapshots: List<MatrixMessageSnapshot>?) :
		MatrixMessageSnapshotClient {
		var lastRequestedRoomId: String? = null
			private set
		var lastRequestedLimit: Int? = null
			private set

		override fun getRecentMessages(roomId: String, limit: Int): List<MatrixMessageSnapshot>? {
			lastRequestedRoomId = roomId
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
