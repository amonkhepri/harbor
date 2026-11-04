package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.connector.ConnectorReactionSummary
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
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.FileMessageContent
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.ImageMessageContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.Reaction
import org.matrix.rustcomponents.sdk.ReactionSenderData
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import java.util.concurrent.TimeUnit

class ReflectiveMatrixHomeserverDiscoveryClientMessagesTest {

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
	fun `redacted events remain between surrounding messages with identity and direction intact`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		val deleted = TimelineItem(
			FakeTimelineEventSpec(
				EventOrTransactionId.EventId("\$deleted:example.org"),
				"@bob:example.org",
				null,
				2_000uL,
				own = true,
				redacted = true,
			),
		)
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent("\$before:example.org", "before", 1_000uL),
					deleted,
					remoteEvent("\$after:example.org", "after", 3_000uL),
				),
			),
		)

		assertEquals(
			listOf(
				MatrixMessageSnapshot("\$before:example.org", "@alice:example.org", "before", 1L, false),
				MatrixMessageSnapshot(
					"\$deleted:example.org",
					"@bob:example.org",
					"",
					2L,
					true,
					type = ConnectorMessageType.DELETED,
				),
				MatrixMessageSnapshot("\$after:example.org", "@alice:example.org", "after", 3L, false),
			),
			client.getRecentMessages("!room:example.org", 3),
		)
		assertEquals(3, FakeMatrixSdkState.eventTimelineItemDestroyCount)
		assertEquals(2, FakeMatrixSdkState.messageContentDestroyCount)
	}

	@Test
	fun `getRecentMessages maps Matrix images to connector photo placeholders`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$image:example.org",
						"private image body",
						2_000uL,
						messageType = MessageType.Image,
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 1)

		assertEquals(ConnectorMessageType.PHOTO, result?.single()?.type)
		assertEquals(1, FakeMatrixSdkState.messageContentDestroyCount)
		assertEquals(1, FakeMatrixSdkState.eventTimelineItemDestroyCount)
	}

	@Test
	fun `getRecentMessages maps Matrix file attachment metadata`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		val fileContent = FileMessageContent(
			filename = "contract.pdf",
			info = FileInfo(
				mimetype = "application/pdf",
				size = 5000uL,
				thumbnailInfo = null,
				thumbnailSource = null,
			),
		)
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$file:example.org",
						"contract.pdf",
						2_000uL,
						messageType = MessageType.File(fileContent),
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 1)
		val msg = result?.single()

		assertEquals(ConnectorMessageType.FILE, msg?.type)
		assertEquals("contract.pdf", msg?.attachment?.fileName)
		assertEquals("application/pdf", msg?.attachment?.mimeType)
		assertEquals(5000L, msg?.attachment?.size)
		assertEquals(1, FakeMatrixSdkState.messageContentDestroyCount)
		assertEquals(1, FakeMatrixSdkState.eventTimelineItemDestroyCount)
	}

	@Test
	fun `getRecentMessages maps Matrix image attachment metadata`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		val imageContent = ImageMessageContent(
			filename = "photo.jpg",
			info = ImageInfo(
				height = null,
				width = null,
				mimetype = "image/jpeg",
				size = 12345uL,
				thumbnailInfo = null,
				thumbnailSource = null,
				blurhash = null,
				isAnimated = null,
			),
		)
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$img:example.org",
						"photo.jpg",
						2_000uL,
						messageType = MessageType.Image(imageContent),
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 1)
		val msg = result?.single()

		assertEquals(ConnectorMessageType.PHOTO, msg?.type)
		assertEquals("photo.jpg", msg?.attachment?.fileName)
		assertEquals("image/jpeg", msg?.attachment?.mimeType)
		assertEquals(12345L, msg?.attachment?.size)
	}

	@Test
	fun `getRecentMessages handles null attachment info without failing`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		val fileContentNoInfo = FileMessageContent(
			filename = "unknown.bin",
			info = null,
		)
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$file:example.org",
						"unknown.bin",
						2_000uL,
						messageType = MessageType.File(fileContentNoInfo),
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 1)
		val msg = result?.single()

		assertEquals(ConnectorMessageType.FILE, msg?.type)
		assertEquals("unknown.bin", msg?.attachment?.fileName)
		assertNull(msg?.attachment?.mimeType)
		assertNull(msg?.attachment?.size)
	}

	@Test
	fun `getRecentMessages preserves Matrix edited state`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent("\$edited:example.org", "edited body", 2_000uL, edited = true),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 1)

		assertTrue(result?.single()?.isEdited == true)
	}

	@Test
	fun `getRecentMessages preserves Matrix reply state and destroys the reply handle`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent("\$reply:example.org", "reply body", 2_000uL, isReply = true),
					remoteEvent("\$plain:example.org", "plain body", 3_000uL),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 2)

		assertEquals(listOf(true, false), result?.map { it.isReply })
		assertEquals(1, FakeMatrixSdkState.inReplyToDetailsDestroyCount)
	}

	@Test
	fun `getRecentMessages maps only non-empty reactions to aggregate counts`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$reacted:example.org",
						"body",
						2_000uL,
						reactions = listOf(
							Reaction("👍", List(2) { ReactionSenderData() }),
							Reaction("", listOf(ReactionSenderData())),
							Reaction("😂", emptyList()),
						),
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 1)

		assertEquals(listOf(ConnectorReactionSummary("👍", 2)), result?.single()?.reactions)
	}

	@Test
	fun `getRecentMessages maps video, audio, file, gallery to FILE placeholders`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$video:example.org",
						"private video body",
						2_000uL,
						messageType = MessageType.Video,
					),
					remoteEvent(
						"\$audio:example.org",
						"private audio body",
						3_000uL,
						messageType = MessageType.Audio,
					),
					remoteEvent(
						"\$file:example.org",
						"private file body",
						4_000uL,
						messageType = MessageType.File,
					),
					remoteEvent(
						"\$gallery:example.org",
						"private gallery body",
						5_000uL,
						messageType = MessageType.Gallery,
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 4)

		assertEquals(
			listOf(
				ConnectorMessageType.FILE,
				ConnectorMessageType.FILE,
				ConnectorMessageType.FILE,
				ConnectorMessageType.FILE,
			),
			result?.map { it.type },
		)
	}

	@Test
	fun `getRecentMessages maps Location, Notice, Emote, and Other to connector text`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$location:example.org",
						"private location body",
						6_000uL,
						messageType = MessageType.Location,
					),
					remoteEvent(
						"\$notice:example.org",
						"private notice body",
						7_000uL,
						messageType = MessageType.Notice,
					),
					remoteEvent(
						"\$emote:example.org",
						"private emote body",
						8_000uL,
						messageType = MessageType.Emote,
					),
					remoteEvent(
						"\$other:example.org",
						"private other body",
						9_000uL,
						messageType = MessageType.Other,
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 4)

		assertEquals(
			List(4) { ConnectorMessageType.TEXT },
			result?.map { it.type },
		)
	}

	@Test
	fun `getRecentMessages preserves edited and reply flags independently of message type`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$file:example.org",
						"private file body",
						2_000uL,
						messageType = MessageType.File,
						edited = true,
						isReply = true,
					),
					remoteEvent(
						"\$image:example.org",
						"private image body",
						3_000uL,
						messageType = MessageType.Image,
						edited = true,
					),
					remoteEvent(
						"\$notice:example.org",
						"private notice body",
						4_000uL,
						messageType = MessageType.Notice,
						isReply = true,
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 3)

		assertEquals(
			listOf(ConnectorMessageType.FILE, ConnectorMessageType.PHOTO, ConnectorMessageType.TEXT),
			result?.map { it.type },
		)
		assertEquals(listOf(true, true, false), result?.map { it.isEdited })
		assertEquals(listOf(true, false, true), result?.map { it.isReply })
		assertEquals(2, FakeMatrixSdkState.inReplyToDetailsDestroyCount)
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
	fun `getRecentMessages deduplicates overlapping timeline events across pagination`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent("\$event2:example.org", "message 2", 2_000uL),
					remoteEvent("\$event3:example.org", "message 3", 3_000uL),
				),
			),
		)
		FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent("\$event1:example.org", "message 1", 1_000uL),
					remoteEvent("\$event2:example.org", "message 2 (updated)", 2_000uL),
					remoteEvent("\$event2:example.org", "message 2 (updated)", 2_000uL),
					remoteEvent("\$event3:example.org", "message 3", 3_000uL),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 3)

		assertEquals(
			listOf(
				MatrixMessageSnapshot("\$event1:example.org", "@alice:example.org", "message 1", 1L, false),
				MatrixMessageSnapshot(
					"\$event2:example.org",
					"@alice:example.org",
					"message 2 (updated)",
					2L,
					false,
				),
				MatrixMessageSnapshot(
					"\$event3:example.org",
					"@alice:example.org",
					"message 3",
					3L,
					false,
				),
			),
			result,
		)
	}

	@Test
	fun `getRecentMessages preserves edited state across pagination duplicates`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$event2:example.org",
						"message 2 (edited)",
						2_000uL,
						edited = true,
					),
					remoteEvent("\$event3:example.org", "message 3", 3_000uL),
				),
			),
		)
		FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(
			TimelineDiff.Reset(
				listOf(
					// Unedited followed by edited
					remoteEvent(
						"\$event1:example.org",
						"message 1 (original)",
						1_000uL,
						edited = false,
					),
					remoteEvent(
						"\$event1:example.org",
						"message 1 (edited)",
						1_000uL,
						edited = true,
					),
					// Edited followed by unedited stale original
					remoteEvent(
						"\$event2:example.org",
						"message 2 (edited)",
						2_000uL,
						edited = true,
					),
					remoteEvent(
						"\$event2:example.org",
						"message 2 (original)",
						2_000uL,
						edited = false,
					),
					remoteEvent("\$event3:example.org", "message 3", 3_000uL),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 3)

		assertEquals(
			listOf(
				MatrixMessageSnapshot(
					"\$event1:example.org",
					"@alice:example.org",
					"message 1 (edited)",
					1L,
					false,
					isEdited = true,
				),
				MatrixMessageSnapshot(
					"\$event2:example.org",
					"@alice:example.org",
					"message 2 (edited)",
					2L,
					false,
					isEdited = true,
				),
				MatrixMessageSnapshot(
					"\$event3:example.org",
					"@alice:example.org",
					"message 3",
					3L,
					false,
				),
			),
			result,
		)
	}

	@Test
	fun `getRecentMessages preserves edited state across initial snapshot duplicates`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$event1:example.org",
						"message 1 (original)",
						1_000uL,
						edited = false,
					),
					remoteEvent(
						"\$event1:example.org",
						"message 1 (edited)",
						1_000uL,
						edited = true,
					),
					remoteEvent(
						"\$event2:example.org",
						"message 2 (edited)",
						2_000uL,
						edited = true,
					),
					remoteEvent(
						"\$event2:example.org",
						"message 2 (original)",
						2_000uL,
						edited = false,
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 2)

		assertEquals(
			listOf(
				MatrixMessageSnapshot(
					"\$event1:example.org",
					"@alice:example.org",
					"message 1 (edited)",
					1L,
					false,
					isEdited = true,
				),
				MatrixMessageSnapshot(
					"\$event2:example.org",
					"@alice:example.org",
					"message 2 (edited)",
					2L,
					false,
					isEdited = true,
				),
			),
			result,
		)
	}

	@Test
	fun `getRecentMessages preserves deleted state across initial snapshot duplicates`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		val deleted1 = TimelineItem(
			FakeTimelineEventSpec(
				EventOrTransactionId.EventId("\$event1:example.org"),
				"@alice:example.org",
				null,
				1_000uL,
				own = false,
				redacted = true,
			),
		)
		val deleted2 = TimelineItem(
			FakeTimelineEventSpec(
				EventOrTransactionId.EventId("\$event2:example.org"),
				"@alice:example.org",
				null,
				2_000uL,
				own = false,
				redacted = true,
			),
		)
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					// Deleted followed by text
					deleted1,
					remoteEvent("\$event1:example.org", "text 1", 1_000uL),
					// Text followed by deleted
					remoteEvent("\$event2:example.org", "text 2", 2_000uL),
					deleted2,
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 2)

		assertEquals(
			listOf(
				MatrixMessageSnapshot(
					"\$event1:example.org",
					"@alice:example.org",
					"",
					1L,
					false,
					type = ConnectorMessageType.DELETED,
				),
				MatrixMessageSnapshot(
					"\$event2:example.org",
					"@alice:example.org",
					"",
					2L,
					false,
					type = ConnectorMessageType.DELETED,
				),
			),
			result,
		)
	}

	@Test
	fun `getRecentMessages preserves deleted state across pagination duplicates`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent("\$event2:example.org", "text 2", 2_000uL),
				),
			),
		)
		val deleted1 = TimelineItem(
			FakeTimelineEventSpec(
				EventOrTransactionId.EventId("\$event1:example.org"),
				"@alice:example.org",
				null,
				1_000uL,
				own = false,
				redacted = true,
			),
		)
		val deleted2 = TimelineItem(
			FakeTimelineEventSpec(
				EventOrTransactionId.EventId("\$event2:example.org"),
				"@alice:example.org",
				null,
				2_000uL,
				own = false,
				redacted = true,
			),
		)
		FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(
			TimelineDiff.Reset(
				listOf(
					// Deleted followed by text
					deleted1,
					remoteEvent("\$event1:example.org", "text 1", 1_000uL),
					// Text followed by deleted
					remoteEvent("\$event2:example.org", "text 2", 2_000uL),
					deleted2,
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 2)

		assertEquals(
			listOf(
				MatrixMessageSnapshot(
					"\$event1:example.org",
					"@alice:example.org",
					"",
					1L,
					false,
					type = ConnectorMessageType.DELETED,
				),
				MatrixMessageSnapshot(
					"\$event2:example.org",
					"@alice:example.org",
					"",
					2L,
					false,
					type = ConnectorMessageType.DELETED,
				),
			),
			result,
		)
	}

	@Test
	fun `getRecentMessages clears reactions when duplicate later state is empty in initial`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$event1:example.org",
						"message 1",
						1_000uL,
						reactions = listOf(Reaction("👍", listOf(ReactionSenderData()))),
					),
					remoteEvent(
						"\$event1:example.org",
						"message 1",
						1_000uL,
						reactions = emptyList(),
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 1)

		assertEquals(
			listOf(
				MatrixMessageSnapshot(
					"\$event1:example.org",
					"@alice:example.org",
					"message 1",
					1L,
					false,
					reactions = emptyList(),
				),
			),
			result,
		)
	}

	@Test
	fun `getRecentMessages clears reactions when duplicate later state is empty in page`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$event1:example.org",
						"message 1",
						1_000uL,
						reactions = listOf(Reaction("👍", listOf(ReactionSenderData()))),
					),
				),
			),
		)
		FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$event1:example.org",
						"message 1",
						1_000uL,
						reactions = emptyList(),
					),
					remoteEvent("\$event2:example.org", "message 2", 2_000uL),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 2)

		assertEquals(
			listOf(
				MatrixMessageSnapshot(
					"\$event1:example.org",
					"@alice:example.org",
					"message 1",
					1L,
					false,
					reactions = emptyList(),
				),
				MatrixMessageSnapshot(
					"\$event2:example.org",
					"@alice:example.org",
					"message 2",
					2L,
					false,
				),
			),
			result,
		)
	}

	@Test
	fun `getRecentMessages preserves edited empty reactions against stale duplicate`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.timelineDiffsToReturn = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$event1:example.org",
						"message 1 (edited)",
						1_000uL,
						edited = true,
						reactions = emptyList(),
					),
				),
			),
		)
		FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(
			TimelineDiff.Reset(
				listOf(
					remoteEvent(
						"\$event1:example.org",
						"message 1 (stale original)",
						1_000uL,
						edited = false,
						reactions = listOf(Reaction("👍", listOf(ReactionSenderData()))),
					),
					remoteEvent(
						"\$event1:example.org",
						"message 1 (edited)",
						1_000uL,
						edited = true,
						reactions = emptyList(),
					),
				),
			),
		)

		val result = client.getRecentMessages("!room:example.org", 1)

		assertEquals(
			listOf(
				MatrixMessageSnapshot(
					"\$event1:example.org",
					"@alice:example.org",
					"message 1 (edited)",
					1L,
					false,
					isEdited = true,
					reactions = emptyList(),
				),
			),
			result,
		)
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
	fun `getRecentMessages preserves initial snapshot when pagination refresh fails`() {
		val expected = listOf(
			MatrixMessageSnapshot("\$newer:example.org", "@alice:example.org", "newer", 2L, false),
		)
		val failures = listOf<Pair<Int, () -> Unit>>(
			1 to { FakeMatrixSdkState.paginationFailureToThrow = IllegalStateException("failed") },
			2 to { FakeMatrixSdkState.timelineDiffsAfterPagination = listOf(TimelineDiff.PopBack) },
		)
		failures.forEach { (expectedListenerCount, configureFailure) ->
			FakeMatrixSdkState.reset()
			val client = loggedInClient()
			FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
			FakeMatrixSdkState.timelineDiffsToReturn = listOf(
				TimelineDiff.Reset(listOf(remoteEvent("\$newer:example.org", "newer", 2_000uL))),
			)
			configureFailure()

			assertEquals(expected, client.getRecentMessages("!room:example.org", 2))
			assertEquals(1, FakeMatrixSdkState.paginationCallCount)
			assertEquals(expectedListenerCount, FakeMatrixSdkState.listenerAddCount)
			assertEquals(expectedListenerCount, FakeMatrixSdkState.taskCancelCount)
			assertEquals(expectedListenerCount, FakeMatrixSdkState.taskCloseCount)
			assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
			assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		}
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
		messageType: MessageType = MessageType.Text(),
		edited: Boolean = false,
		isReply: Boolean = false,
		reactions: List<Reaction> = emptyList(),
	): TimelineItem = TimelineItem(
		FakeTimelineEventSpec(
			EventOrTransactionId.EventId(eventId),
			if (own) "@bob:example.org" else "@alice:example.org",
			body,
			timestamp,
			own,
			messageType,
			edited,
			isReply,
			reactions,
		),
	)

	private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
		while (!condition() && System.nanoTime() < deadline) Thread.sleep(10L)
		assertTrue("condition not met before timeout", condition())
	}
}
