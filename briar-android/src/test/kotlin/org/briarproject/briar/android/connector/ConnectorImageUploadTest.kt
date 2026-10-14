package org.briarproject.briar.android.connector

import android.app.Application
import android.view.LayoutInflater
import android.widget.TextView
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.briarproject.bramble.test.SettableClock
import org.briarproject.briar.R
import org.briarproject.briar.api.connector.ConnectorImageSender
import org.briarproject.briar.api.connector.ConnectorMessageReadResult.Success
import org.briarproject.briar.api.connector.ConnectorMessageType
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.briarproject.briar.matrix.MatrixRoomConnector
import org.briarproject.briar.matrix.ReflectiveMatrixHomeserverDiscoveryClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.FakeTimelineEventSpec
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.RoomException
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.UploadSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorImageUploadTest {
	@get:Rule val rule = InstantTaskExecutorRule()
	private val roomId = "!open:example.org"
	private val selection = "content://picker/image/1"

	@After fun reset() {
		FakeMatrixSdkState.reset()
	}

	@Test fun pickedImageReachesOpenRoomAndRendersPhotoAfterJoin() {
		val connector = connector()
		val state = ConnectorImageUploadState()
		assertTrue(connector.isAuthorized())
		assertTrue(state.begin(selection))
		assertFalse(state.begin("content://picker/image/2"))
		FakeMatrixSdkState.onImageJoined = {
			FakeMatrixSdkState.timelineDiffsToReturn = listOf(
				TimelineDiff.Reset(
					listOf(
						TimelineItem(
							FakeTimelineEventSpec(
								EventOrTransactionId.EventId("\$image"),
								"@self:example.org",
								"image.png",
								1000uL,
								true,
								MessageType.Image,
							),
						),
					),
				),
			)
		}
		val bytes = byteArrayOf(1, 2, 3)
		state.complete(
			sendConnectorImage(connector, roomId, "image/png", "image.png") {
				ByteArrayInputStream(bytes)
			},
		)
		assertEquals(ConnectorImageUploadState.Status.SENT, state.status.value)
		assertNull(state.pendingImage)
		assertEquals(listOf(roomId), FakeMatrixSdkState.closedRoomHandles)
		val source = FakeMatrixSdkState.lastSendImageParams!!.source as UploadSource.Data
		assertArrayEquals(bytes, source.bytes)
		assertEquals("image.png", source.filename)
		assertEquals("image/png", FakeMatrixSdkState.lastSendImageInfo!!.mimetype)
		assertEquals(3uL, FakeMatrixSdkState.lastSendImageInfo!!.size)
		assertNull(FakeMatrixSdkState.lastSendImageParams!!.caption)
		val messages = (connector.getRecentMessageReadResult(roomId, 50) as Success).messages
		val row = ConnectorConversationMessageItem.fromMessages(messages).single()
		assertEquals(ConnectorMessageType.PHOTO, row.type)
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		ConnectorConversationAdapter.MessageViewHolder(view).bind(row)
		assertEquals(
			view.resources.getString(R.string.connector_message_photo),
			view.findViewById<TextView>(R.id.connectorMessageText).text.toString(),
		)
	}

	@Test fun failedSendRetainsSelectionForExplicitRetry() {
		val connector = connector()
		val state = ConnectorImageUploadState()
		state.begin(selection)
		FakeMatrixSdkState.joinFailureToThrow = RoomException.Generic("failure")
		state.complete(
			sendConnectorImage(connector, roomId, "image/png", "image.png") {
				ByteArrayInputStream(byteArrayOf(1))
			},
		)
		assertEquals(ConnectorImageUploadState.Status.FAILED, state.status.value)
		assertEquals(selection, state.pendingImage)
		val restored = ConnectorImageUploadState()
		restored.restore(state.pendingImage)
		assertEquals(ConnectorImageUploadState.Status.FAILED, restored.status.value)
		FakeMatrixSdkState.joinFailureToThrow = null
		assertTrue(restored.begin(restored.pendingImage!!))
		restored.complete(
			sendConnectorImage(connector, roomId, "image/png", "image.png") {
				ByteArrayInputStream(byteArrayOf(1))
			},
		)
		assertEquals(ConnectorImageUploadState.Status.SENT, restored.status.value)
		assertNull(restored.pendingImage)
	}

	@Test fun oversizedOrNonImageProviderContentNeverReachesSdk() {
		val connector = connector()
		var closed = false
		assertFalse(
			sendConnectorImage(connector, roomId, "image/png", "image.png") {
				object : ByteArrayInputStream(ByteArray(ConnectorImageSender.MAX_IMAGE_BYTES + 1)) {
					override fun close() {
						closed = true
					}
				}
			},
		)
		assertTrue(closed)
		assertFalse(
			sendConnectorImage(connector, roomId, "text/plain", "image.png") {
				error("Non-image streams must not be opened")
			},
		)
		assertEquals(0, FakeMatrixSdkState.sendImageCallCount)
	}

	@Test fun acknowledgeResetsSentStatusSoRotationDoesNotReplayIt() {
		val state = ConnectorImageUploadState()
		state.begin(selection)
		state.complete(true)
		assertEquals(ConnectorImageUploadState.Status.SENT, state.status.value)

		state.acknowledge()

		assertEquals(ConnectorImageUploadState.Status.IDLE, state.status.value)
		assertNull(state.pendingImage)
	}

	@Test fun acknowledgeResetsFailedStatusButKeepsSelectionForRetry() {
		val state = ConnectorImageUploadState()
		state.begin(selection)
		state.complete(false)

		state.acknowledge()

		assertEquals(ConnectorImageUploadState.Status.IDLE, state.status.value)
		assertEquals(selection, state.pendingImage)
	}

	@Test fun acknowledgeIsANoOpWhileSendingSoAnInFlightUploadSurvivesRecreation() {
		val state = ConnectorImageUploadState()
		state.begin(selection)

		state.acknowledge()

		assertEquals(ConnectorImageUploadState.Status.SENDING, state.status.value)
	}

	private fun connector(): MatrixRoomConnector {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()
		assertEquals(
			MatrixAuthSession.RecoverableErrorDetail.NONE,
			client.login("https://matrix.example.org", "alice", "password"),
		)
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(roomId))
		val auth = object : MatrixAuthSession {
			override fun getSnapshot() =
				MatrixAuthSession.Snapshot(MatrixAuthState.READY, MatrixAuthSession.RecoverableErrorDetail.NONE)
			override fun start() {}
			override fun submitHomeserver(homeserverUrl: String) {}
			override fun submitCredentials(username: String, password: String) {}
			override fun logout() {}
			override fun close() {}
		}
		return MatrixRoomConnector(client, auth, client, client, SettableClock(AtomicLong(0L)))
	}
}
