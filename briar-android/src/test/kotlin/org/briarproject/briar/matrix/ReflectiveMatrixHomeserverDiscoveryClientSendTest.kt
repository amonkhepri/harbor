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
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.RoomException
import org.matrix.rustcomponents.sdk.UploadSource

class ReflectiveMatrixHomeserverDiscoveryClientSendTest {

	@After
	fun tearDown() {
		FakeMatrixSdkState.reset()
	}

	@Test
	fun `sendTextMessage returns false when no session is retained`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()

		assertFalse(client.sendTextMessage("!room:example.org", "hello"))
		assertEquals(0, FakeMatrixSdkState.sendCallCount)
	}

	@Test
	fun `sendTextMessage returns false when the room cannot be found`() {
		val client = loggedInClient()

		assertFalse(client.sendTextMessage("!missing:example.org", "hello"))
		assertEquals(0, FakeMatrixSdkState.timelineCallCount)
		assertEquals(0, FakeMatrixSdkState.sendCallCount)
	}

	@Test
	fun `sendTextMessage builds a text message and fires it over the timeline`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))

		val sent = client.sendTextMessage("!room:example.org", "hello there")

		assertTrue(sent)
		assertEquals(1, FakeMatrixSdkState.createMessageContentCallCount)
		val createdMessageType = FakeMatrixSdkState.lastCreatedMessageType
		assertTrue(createdMessageType is MessageType.Text)
		assertEquals("hello there", (createdMessageType as MessageType.Text).content.body)
		assertEquals(1, FakeMatrixSdkState.sendCallCount)
		assertEquals(FakeMatrixSdkState.lastSentContent?.messageType, createdMessageType)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomMessageEventContentCloseCount)
		assertEquals(1, FakeMatrixSdkState.sendHandleCloseCount)
	}

	@Test
	fun `sendTextMessage returns false and still releases the room and timeline when send throws`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.sendFailureToThrow = ClientException.Generic("failed", "failed")

		val sent = client.sendTextMessage("!room:example.org", "hello there")

		assertFalse(sent)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomMessageEventContentCloseCount)
	}

	@Test
	fun `sendTextMessage bridges an asynchronously completed send`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.sendSuspendAsynchronously = true
		FakeMatrixSdkState.sendDelayMs = 10L

		val sent = client.sendTextMessage("!room:example.org", "hello there")

		assertTrue(sent)
		assertEquals(1, FakeMatrixSdkState.sendCallCount)
	}

	@Test
	fun `sendImageMessage returns false when no session is retained`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()

		assertFalse(
			client.sendImageMessage("!room:example.org", byteArrayOf(1), "image.jpg", "image/jpeg"),
		)
		assertEquals(0, FakeMatrixSdkState.sendImageCallCount)
	}

	@Test
	fun `sendImageMessage returns false when the room cannot be found`() {
		val client = loggedInClient()

		assertFalse(
			client.sendImageMessage("!missing:example.org", byteArrayOf(1), "image.jpg", "image/jpeg"),
		)
		assertEquals(0, FakeMatrixSdkState.timelineCallCount)
		assertEquals(0, FakeMatrixSdkState.sendImageCallCount)
	}

	@Test
	fun `sendImageMessage builds an image upload and joins it over the timeline`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		val imageBytes = byteArrayOf(1, 2, 3)

		val sent = client.sendImageMessage("!room:example.org", imageBytes, "image.jpg", "image/jpeg")

		assertTrue(sent)
		assertEquals(1, FakeMatrixSdkState.sendImageCallCount)
		val source = FakeMatrixSdkState.lastSendImageParams?.source as? UploadSource.Data
		assertEquals(imageBytes, source?.bytes)
		assertEquals("image.jpg", source?.filename)
		assertEquals("image/jpeg", FakeMatrixSdkState.lastSendImageInfo?.mimetype)
		assertEquals(3uL, FakeMatrixSdkState.lastSendImageInfo?.size)
		assertEquals(null, FakeMatrixSdkState.lastSendImageThumbnailSource)
		assertEquals(1, FakeMatrixSdkState.joinCallCount)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(1, FakeMatrixSdkState.imageInfoDestroyCount)
		assertEquals(1, FakeMatrixSdkState.sendAttachmentJoinHandleCloseCount)
	}

	@Test
	fun `sendImageMessage returns false and still releases the room and timeline when join throws`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.joinFailureToThrow = RoomException.Generic("failed")

		val sent = client.sendImageMessage("!room:example.org", byteArrayOf(1), "image.jpg", "image/jpeg")

		assertFalse(sent)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(1, FakeMatrixSdkState.roomCloseCount)
		assertEquals(1, FakeMatrixSdkState.imageInfoDestroyCount)
		assertEquals(1, FakeMatrixSdkState.sendAttachmentJoinHandleCloseCount)
	}

	@Test
	fun `sendImageMessage bridges an asynchronously completed join`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.joinSuspendAsynchronously = true
		FakeMatrixSdkState.joinDelayMs = 10L

		val sent = client.sendImageMessage("!room:example.org", byteArrayOf(1), "image.jpg", "image/jpeg")

		assertTrue(sent)
		assertEquals(1, FakeMatrixSdkState.joinCallCount)
	}

	@Test
	fun `image send rejects non joined and encrypted rooms before uploading`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn =
			listOf(
				FakeRoomSpec("!room:example.org", membership = org.matrix.rustcomponents.sdk.Membership.LEFT),
			)
		assertFalse(
			client.sendImageMessage("!room:example.org", byteArrayOf(1), "image.jpg", "image/jpeg"),
		)
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.encryptionState = org.matrix.rustcomponents.sdk.EncryptionState.ENCRYPTED
		assertFalse(
			client.sendImageMessage("!room:example.org", byteArrayOf(1), "image.jpg", "image/jpeg"),
		)
		FakeMatrixSdkState.encryptionState = org.matrix.rustcomponents.sdk.EncryptionState.UNKNOWN
		assertFalse(
			client.sendImageMessage("!room:example.org", byteArrayOf(1), "image.jpg", "image/jpeg"),
		)
		assertEquals(0, FakeMatrixSdkState.sendImageCallCount)
	}

	@Test
	fun `failed image join cancels the upload before releasing its handle`() {
		val client = loggedInClient()
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.joinFailureToThrow = RoomException.Generic("failed")
		assertFalse(
			client.sendImageMessage("!room:example.org", byteArrayOf(1), "image.jpg", "image/jpeg"),
		)
		assertEquals(1, FakeMatrixSdkState.imageCancelCount)
		assertEquals(1, FakeMatrixSdkState.sendAttachmentJoinHandleCloseCount)
	}

	@Test
	fun `image upload timeout cancels and releases handle despite late join completion`() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient(discoveryTimeoutMs = 5L)
		assertEquals(NONE, client.login("https://matrix.example.org", "alice", "password"))
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
		FakeMatrixSdkState.joinSuspendAsynchronously = true
		FakeMatrixSdkState.joinDelayMs = 50L
		assertFalse(
			client.sendImageMessage("!room:example.org", byteArrayOf(1), "image.jpg", "image/jpeg"),
		)
		assertEquals(1, FakeMatrixSdkState.imageCancelCount)
		assertEquals(1, FakeMatrixSdkState.sendAttachmentJoinHandleCloseCount)
		assertTrue(FakeMatrixSdkState.joinFinished.await(2, java.util.concurrent.TimeUnit.SECONDS))
		assertEquals(1, FakeMatrixSdkState.sendAttachmentJoinHandleCloseCount)
	}

	private fun loggedInClient(): ReflectiveMatrixHomeserverDiscoveryClient =
		ReflectiveMatrixHomeserverDiscoveryClient().also { client ->
			assertEquals(NONE, client.login("https://matrix.example.org", "alice", "swordfish"))
		}
}
