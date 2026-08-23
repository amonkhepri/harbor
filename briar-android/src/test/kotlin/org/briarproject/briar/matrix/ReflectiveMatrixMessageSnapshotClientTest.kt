package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.FakeTimelineEventSpec
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import java.util.concurrent.TimeUnit

class ReflectiveMatrixMessageSnapshotClientTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test
	fun `getRecentMessages distinguishes missing session and room from an empty snapshot`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()

		assertEquals(emptyList<Any>(), client.getRecentMessages("!missing:example.org", 0))
		assertNull(client.getRecentMessages("!missing:example.org", 20))
		assertEquals(NONE, client.login("https://matrix.example.org", "alice", "swordfish"))
		assertNull(client.getRecentMessages("!missing:example.org", 20))
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		assertEquals(emptyList<Any>(), client.getRecentMessages("!room:example.org", 0))
		assertEquals(0, FakeMatrixSdkState.timelineCallCount)
		assertEquals(0, FakeMatrixSdkState.roomCloseCount)
	}

	@Test
	fun `getRecentMessages applies every diff and maps bounded remote text events`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineSuspendAsynchronously = true
		FakeMatrixSdkState.timelineDelayMs = 10L
		FakeMatrixSdkState.listenerRegistrationSuspendAsynchronously = true
		FakeMatrixSdkState.listenerRegistrationDelayMs = 10L
		FakeMatrixSdkState.timelineUpdateDelayMs = 10L
		FakeMatrixSdkState.timelineDiffsToReturn = allDiffVariants()

		val result = client.getRecentMessages("!room:example.org", 2)

		assertEquals(
			listOf(
				MatrixMessageSnapshot("\$a:example.org", "@alice:example.org", "older", 3L, false),
				MatrixMessageSnapshot(
					"\$b:example.org",
					"@bob:example.org",
					"newer",
					18_446_744_073_709_551L,
					true,
				),
			),
			result,
		)
		assertEquals(1, FakeMatrixSdkState.timelineCallCount)
		assertEquals(1, FakeMatrixSdkState.listenerAddCount)
		assertEquals(0, FakeMatrixSdkState.paginationCallCount)
		assertEquals(listOf("listener"), FakeMatrixSdkState.timelineOperationOrder)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.taskCancelCount)
		assertEquals(1, FakeMatrixSdkState.taskCloseCount)
		assertEquals(11, FakeMatrixSdkState.timelineDiffDestroyCount)
		assertEquals(10, FakeMatrixSdkState.timelineItemCloseCount)
		assertEquals(4, FakeMatrixSdkState.eventTimelineItemDestroyCount)
		assertEquals(3, FakeMatrixSdkState.messageContentDestroyCount)
		assertEquals(1, FakeMatrixSdkState.otherContentDestroyCount)
	}

	@Test
	fun `getRecentMessages paginates once then maps a fresh full snapshot`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(listOf(remoteEvent("\$newer:example.org", "newer", 2_000uL))),
		)
		FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent("\$older:example.org", "older", 1_000uL),
					remoteEvent("\$newer:example.org", "newer", 2_000uL),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 2)

		assertEquals(
			listOf(
				MatrixMessageSnapshot("\$older:example.org", "@alice:example.org", "older", 1L, false),
				MatrixMessageSnapshot("\$newer:example.org", "@alice:example.org", "newer", 2L, false),
			),
			result,
		)
		assertEquals(1, FakeMatrixSdkState.paginationCallCount)
		assertEquals(listOf(1), FakeMatrixSdkState.paginationRequestedEventCounts)
		assertEquals(
			listOf("listener", "paginateBackwards", "listener"),
			FakeMatrixSdkState.timelineOperationOrder,
		)
		assertEquals(2, FakeMatrixSdkState.listenerAddCount)
		assertEquals(2, FakeMatrixSdkState.taskCancelCount)
		assertEquals(2, FakeMatrixSdkState.taskCloseCount)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(2, FakeMatrixSdkState.timelineDiffDestroyCount)
		assertEquals(3, FakeMatrixSdkState.timelineItemCloseCount)
		assertEquals(3, FakeMatrixSdkState.eventTimelineItemDestroyCount)
		assertEquals(3, FakeMatrixSdkState.messageContentDestroyCount)
	}

	@Test
	fun `getRecentMessages clamps one pagination request to unsigned short range`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(TimelineDiff.Reset(emptyList()))
		FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(TimelineDiff.Reset(emptyList()))

		assertEquals(emptyList<Any>(), client.getRecentMessages("!room:example.org", Int.MAX_VALUE))
		assertEquals(1, FakeMatrixSdkState.paginationCallCount)
		assertEquals(listOf(UShort.MAX_VALUE.toInt()), FakeMatrixSdkState.paginationRequestedEventCounts)
	}

	@Test
	fun `getRecentMessages maps pagination and second snapshot failures to null`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(listOf(remoteEvent("\$newer:example.org", "newer", 2_000uL))),
		)
		FakeMatrixSdkState.paginationFailureToThrow = IllegalStateException("pagination failed")

		assertNull(client.getRecentMessages("!room:example.org", 2))
		assertEquals(1, FakeMatrixSdkState.paginationCallCount)
		assertEquals(1, FakeMatrixSdkState.listenerAddCount)
		assertEquals(1, FakeMatrixSdkState.taskCancelCount)
		assertEquals(1, FakeMatrixSdkState.taskCloseCount)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)

		FakeMatrixSdkState.reset()
		val secondClient = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(listOf(remoteEvent("\$newer:example.org", "newer", 2_000uL))),
		)
		FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(TimelineDiff.PopBack)

		assertNull(secondClient.getRecentMessages("!room:example.org", 2))
		assertEquals(1, FakeMatrixSdkState.paginationCallCount)
		assertEquals(2, FakeMatrixSdkState.listenerAddCount)
		assertEquals(2, FakeMatrixSdkState.taskCancelCount)
		assertEquals(2, FakeMatrixSdkState.taskCloseCount)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
	}

	@Test
	fun `getRecentMessages times out and destroys a late listener update`() {
		val client = loggedInClient(timeoutMs = 30L)
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineUpdateDelayMs = 200L
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(listOf(remoteEvent("\$late:example.org", "late", 5_000uL))),
		)

		val startedAt = System.nanoTime()
		val result = client.getRecentMessages("!room:example.org", 20)
		val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

		assertNull(result)
		assertTrue("listener wait must stay bounded", elapsedMs < 500L)
		assertEquals(1, FakeMatrixSdkState.taskCancelCount)
		assertEquals(1, FakeMatrixSdkState.taskCloseCount)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		awaitCondition(timeoutMs = 2_000L) {
			FakeMatrixSdkState.timelineDiffDestroyCount == 1
		}
		assertEquals(1, FakeMatrixSdkState.timelineItemCloseCount)
		assertEquals(0, FakeMatrixSdkState.eventTimelineItemDestroyCount)
	}

	private fun loggedInClient(timeoutMs: Long = 1_000L): ReflectiveMatrixHomeserverDiscoveryClient =
		ReflectiveMatrixHomeserverDiscoveryClient(discoveryTimeoutMs = timeoutMs).also { client ->
			assertEquals(
				NONE,
				client.login("https://matrix.example.org", "alice", "swordfish"),
			)
		}

	private fun allDiffVariants(): List<TimelineDiff> = listOf(
		TimelineDiff.Reset(
			listOf(
				remoteEvent("\$reset-a:example.org", "reset-a", 1_000uL),
				remoteEvent("\$reset-b:example.org", "reset-b", 2_000uL),
			),
		),
		TimelineDiff.Clear,
		TimelineDiff.PushBack(remoteEvent("\$a:example.org", "older", 3_999uL)),
		TimelineDiff.PushFront(TimelineItem(null)),
		TimelineDiff.Append(
			listOf(
				remoteEvent("\$b:example.org", "newer", ULong.MAX_VALUE, own = true),
				TimelineItem(
					FakeTimelineEventSpec(
						EventOrTransactionId.TransactionId("local-transaction"),
						"@alice:example.org",
						"local",
						4_000uL,
						true,
					),
				),
				TimelineItem(
					FakeTimelineEventSpec(
						EventOrTransactionId.EventId("\$state:example.org"),
						"@state:example.org",
						null,
						4_500uL,
					),
				),
				remoteEvent("\$tail:example.org", "tail", 5_000uL),
			),
		),
		TimelineDiff.Insert(0u, remoteEvent("\$insert:example.org", "insert", 6_000uL)),
		TimelineDiff.Set(0u, remoteEvent("\$set:example.org", "set", 7_000uL)),
		TimelineDiff.Remove(0u),
		TimelineDiff.PopBack,
		TimelineDiff.PopFront,
		TimelineDiff.Truncate(4u),
	)

	private fun remoteEvent(
		eventId: String,
		body: String,
		timestamp: ULong,
		own: Boolean = false,
	): TimelineItem = TimelineItem(
		FakeTimelineEventSpec(
			EventOrTransactionId.EventId(eventId),
			if (own) "@bob:example.org" else "@alice:example.org",
			body,
			timestamp,
			own,
		),
	)

	private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
		while (!condition() && System.nanoTime() < deadline) Thread.sleep(10L)
		assertTrue("condition not met before timeout", condition())
	}
}
