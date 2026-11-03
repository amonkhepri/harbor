package org.briarproject.briar.matrix

import org.briarproject.bramble.api.system.Clock
import org.briarproject.briar.api.connector.ConnectorRoomObserver.RoomSubscription
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixAuthSession.Snapshot
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.FakeTimelineEventSpec
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem

class MatrixRoomConnectorObserverTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test
	fun observeRoomFailsClosedWhenConnectorIsNotAuthorized() {
		var clientObserved = false
		val fakeSnapshotClient = object : MatrixMessageSnapshotClient {
			override fun getRecentMessages(roomId: String, limit: Int) = null
			override fun observeRoom(roomId: String, onUpdate: () -> Unit): RoomSubscription {
				clientObserved = true
				return object : RoomSubscription {
					override fun cancel() {}
					override fun close() {}
				}
			}
		}
		val unauthSession = FakeMatrixAuthSession(MatrixAuthState.HOMESERVER_ENTRY)
		val connector = MatrixRoomConnector(
			object : MatrixRoomSnapshotClient {
				override fun getJoinedRooms(limit: Int) = emptyList<MatrixRoomSnapshot>()
			},
			unauthSession,
			fakeSnapshotClient,
			object : MatrixMessageSendClient {
				override fun sendTextMessage(roomId: String, text: String) = false
				override fun sendImageMessage(r: String, b: ByteArray, f: String, m: String) = false
				override fun markRead(roomId: String) = false
				override fun deleteMessage(roomId: String, eventId: String) = false
			},
			object : Clock {
				override fun currentTimeMillis() = 0L
				override fun sleep(ms: Long) {}
			},
		)

		val subscription = connector.observeRoom("!room:example.org") {}
		assertNull(subscription)
		assertEquals(false, clientObserved)
	}

	@Test
	fun observeRoomDelegatesToMessageSnapshotClientWhenAuthorized() {
		var clientObservedRoomId: String? = null
		val expectedSubscription = object : RoomSubscription {
			override fun cancel() {}
			override fun close() {}
		}
		val fakeSnapshotClient = object : MatrixMessageSnapshotClient {
			override fun getRecentMessages(roomId: String, limit: Int) = null
			override fun observeRoom(roomId: String, onUpdate: () -> Unit): RoomSubscription {
				clientObservedRoomId = roomId
				return expectedSubscription
			}
		}
		val readySession = FakeMatrixAuthSession(MatrixAuthState.READY)
		val connector = MatrixRoomConnector(
			object : MatrixRoomSnapshotClient {
				override fun getJoinedRooms(limit: Int) = emptyList<MatrixRoomSnapshot>()
			},
			readySession,
			fakeSnapshotClient,
			object : MatrixMessageSendClient {
				override fun sendTextMessage(roomId: String, text: String) = false
				override fun sendImageMessage(r: String, b: ByteArray, f: String, m: String) = false
				override fun markRead(roomId: String) = false
				override fun deleteMessage(roomId: String, eventId: String) = false
			},
			object : Clock {
				override fun currentTimeMillis() = 0L
				override fun sleep(ms: Long) {}
			},
		)

		val subscription = connector.observeRoom("!room:example.org") {}
		assertEquals(expectedSubscription, subscription)
		assertEquals("!room:example.org", clientObservedRoomId)
	}

	@Test
	fun reflectiveClientObservesRemoteEventsAndStopsOnCancelAndLogout() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient(discoveryTimeoutMs = 1_000L)
		client.login("https://matrix.example.org", "alice", "swordfish")
		FakeMatrixSdkState.roomsToReturn = listOf(
			FakeRoomSpec("!room:example.org", membership = Membership.JOINED),
		)

		var updateCount = 0
		val subscription = client.observeRoom("!room:example.org") {
			updateCount++
		}
		assertNotNull(subscription)

		// Initial addListener delivers baseline snapshot; onUpdate must not fire on registration
		assertEquals(0, updateCount)

		// Remote send delivers a new diff to active timeline listeners
		val eventSpec = FakeTimelineEventSpec(
			EventOrTransactionId.EventId("\$remote1:example.org"),
			"@bob:example.org",
			"hello",
			1_700_000_000_000uL,
		)
		FakeMatrixSdkState.simulateRemoteEvent(listOf(TimelineDiff.PushBack(TimelineItem(eventSpec))))
		assertEquals(1, updateCount)

		// Second remote send delivers another update
		FakeMatrixSdkState.simulateRemoteEvent(listOf(TimelineDiff.PushBack(TimelineItem(eventSpec))))
		assertEquals(2, updateCount)

		// Cancellation stops future remote event callbacks
		subscription!!.cancel()
		FakeMatrixSdkState.simulateRemoteEvent(listOf(TimelineDiff.PushBack(TimelineItem(eventSpec))))
		assertEquals(2, updateCount)
	}

	@Test
	fun reflectiveClientStopsCallbacksOnLogout() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient(discoveryTimeoutMs = 1_000L)
		client.login("https://matrix.example.org", "alice", "swordfish")
		FakeMatrixSdkState.roomsToReturn = listOf(
			FakeRoomSpec("!room:example.org", membership = Membership.JOINED),
		)

		var updateCount = 0
		val subscription = client.observeRoom("!room:example.org") {
			updateCount++
		}
		assertNotNull(subscription)
		assertEquals(0, updateCount)

		// Logout cancels all active room subscriptions and disposes listeners
		client.logout()

		val eventSpec = FakeTimelineEventSpec(
			EventOrTransactionId.EventId("\$remote2:example.org"),
			"@bob:example.org",
			"after logout",
			1_700_000_000_000uL,
		)
		FakeMatrixSdkState.simulateRemoteEvent(listOf(TimelineDiff.PushBack(TimelineItem(eventSpec))))
		assertEquals(0, updateCount)
	}

	@Test
	fun logoutRunsNativeCancelOutsideLifecycleLock() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient(discoveryTimeoutMs = 1_000L)
		client.login("https://matrix.example.org", "alice", "swordfish")
		FakeMatrixSdkState.roomsToReturn = listOf(
			FakeRoomSpec("!a:example.org", membership = Membership.JOINED),
			FakeRoomSpec("!b:example.org", membership = Membership.JOINED),
		)
		assertNotNull(client.observeRoom("!a:example.org") {})
		assertNotNull(client.observeRoom("!b:example.org") {})
		val eventSpec = FakeTimelineEventSpec(
			EventOrTransactionId.EventId("\$inflight:example.org"),
			"@bob:example.org",
			"in flight",
			1_700_000_000_000uL,
		)
		// Emulate an SDK whose native cancel waits for in-flight callbacks on its shared
		// dispatcher thread. If teardown held lifecycleLock, the other room's callback would
		// block on it and this wait would time out.
		var callbackBlocked = false
		FakeMatrixSdkState.onTaskCancel = {
			val dispatcher = Thread {
				FakeMatrixSdkState.simulateRemoteEvent(
					listOf(TimelineDiff.PushBack(TimelineItem(eventSpec))),
				)
			}
			dispatcher.start()
			dispatcher.join(2_000L)
			if (dispatcher.isAlive) callbackBlocked = true
		}

		val cancelsBefore = FakeMatrixSdkState.taskCancelCount
		client.logout()

		assertFalse(callbackBlocked)
		assertEquals(cancelsBefore + 2, FakeMatrixSdkState.taskCancelCount)
	}

	private class FakeMatrixAuthSession(var state: MatrixAuthState) : MatrixAuthSession {
		override fun getSnapshot(): Snapshot = Snapshot(state, NONE)
		override fun start() {}
		override fun submitHomeserver(homeserverUrl: String) {}
		override fun submitCredentials(username: String, password: String) {}
		override fun logout() {}
		override fun close() {}
	}
}
