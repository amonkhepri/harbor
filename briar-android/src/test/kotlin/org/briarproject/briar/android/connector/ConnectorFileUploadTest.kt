package org.briarproject.briar.android.connector

import android.app.Application
import android.view.LayoutInflater
import android.widget.TextView
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.briarproject.bramble.test.SettableClock
import org.briarproject.briar.R
import org.briarproject.briar.api.connector.ConnectorFileSender
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
class ConnectorFileUploadTest {
	@get:Rule val rule = InstantTaskExecutorRule()
	private val roomId = "!open:example.org"

	@After fun reset() {
		FakeMatrixSdkState.reset()
	}

	@Test fun pickedDocumentReachesOpenRoomAndRendersFileAfterJoin() {
		val connector = connector()
		val state = ConnectorFileUploadState()
		assertTrue(connector.isAuthorized())
		state.pick(connector.prepareFileSend(roomId)!!)
		val prepared = state.choose(true)!!
		FakeMatrixSdkState.onImageJoined = {
			FakeMatrixSdkState.timelineDiffsToReturn = listOf(
				TimelineDiff.Reset(
					listOf(
						TimelineItem(
							FakeTimelineEventSpec(
								EventOrTransactionId.EventId("\$file"),
								"@self:example.org",
								"document",
								1000uL,
								true,
								MessageType.File,
							),
						),
					),
				),
			)
		}
		val bytes = byteArrayOf(1, 2, 3)
		state.complete(
			sendConnectorFile(prepared, "application/pdf") {
				ByteArrayInputStream(bytes)
			},
		)
		assertEquals(ConnectorFileUploadState.Status.SENT, state.status.value)
		assertEquals(listOf(roomId), FakeMatrixSdkState.closedRoomHandles)
		val source = FakeMatrixSdkState.lastSendFileParams!!.source as UploadSource.Data
		assertArrayEquals(bytes, source.bytes)
		assertEquals("document", source.filename)
		assertEquals("application/pdf", FakeMatrixSdkState.lastSendFileInfo!!.mimetype)
		assertEquals(3uL, FakeMatrixSdkState.lastSendFileInfo!!.size)
		assertNull(FakeMatrixSdkState.lastSendFileParams!!.caption)
		val messages = (connector.getRecentMessageReadResult(roomId, 50) as Success).messages
		val row = ConnectorConversationMessageItem.fromMessages(messages).single()
		assertEquals(ConnectorMessageType.FILE, row.type)
		val view = LayoutInflater.from(RuntimeEnvironment.getApplication())
			.inflate(R.layout.list_item_connector_message, null)
		ConnectorConversationAdapter.MessageViewHolder(view).bind(row)
		assertEquals(
			view.resources.getString(R.string.connector_message_file),
			view.findViewById<TextView>(R.id.connectorMessageText).text.toString(),
		)
	}

	@Test fun cancelledPickerSendsNothing() {
		val state = ConnectorFileUploadState()
		state.pick(connector().prepareFileSend(roomId)!!)
		assertNull(state.choose(false))
		assertEquals(ConnectorFileUploadState.Status.IDLE, state.status.value)
		assertEquals(0, FakeMatrixSdkState.sendFileCallCount)
	}

	@Test fun uploadFailureReportsFailedAndReleasesOwnedHandles() {
		val state = ConnectorFileUploadState()
		state.pick(connector().prepareFileSend(roomId)!!)
		FakeMatrixSdkState.joinFailureToThrow = RoomException.Generic("failure")
		state.complete(
			sendConnectorFile(state.choose(true)!!, "text/plain") {
				ByteArrayInputStream(byteArrayOf(1))
			},
		)
		assertEquals(ConnectorFileUploadState.Status.FAILED, state.status.value)
		assertEquals(1, FakeMatrixSdkState.imageCancelCount)
		assertEquals(1, FakeMatrixSdkState.sendAttachmentJoinHandleCloseCount)
		assertEquals(1, FakeMatrixSdkState.fileInfoDestroyCount)
		assertEquals(1, FakeMatrixSdkState.timelineCloseCount)
		assertEquals(listOf(roomId), FakeMatrixSdkState.closedRoomHandles)
	}

	@Test fun unknownSizeOversizedStreamIsBoundedAndClosed() {
		var read = 0
		var closed = false
		val prepared = connector().prepareFileSend(roomId)!!
		assertFalse(
			sendConnectorFile(prepared, "application/pdf") {
				object : java.io.InputStream() {
					override fun read(): Int {
						read++
						return 1
					}
					override fun close() {
						closed = true
					}
				}
			},
		)
		assertTrue(closed)
		assertTrue(read <= ConnectorFileSender.MAX_FILE_BYTES + 8192)
		assertEquals(0, FakeMatrixSdkState.sendFileCallCount)
	}

	@Test fun emptyUnsupportedAndBrokenProvidersFailWithoutSending() {
		val prepared = connector().prepareFileSend(roomId)!!
		assertFalse(sendConnectorFile(prepared, "*/*") { error("must not open") })
		assertFalse(sendConnectorFile(prepared, "text/plain") { ByteArrayInputStream(byteArrayOf()) })
		var closed = false
		assertFalse(
			sendConnectorFile(prepared, "text/plain") {
				object : java.io.InputStream() {
					override fun read(): Int = throw java.io.IOException()
					override fun close() {
						closed = true
					}
				}
			},
		)
		assertTrue(closed)
		assertEquals(0, FakeMatrixSdkState.sendFileCallCount)
	}

	@Test fun sessionReplacementDuringProviderReadRejectsPreparedSend() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()
		client.login("https://matrix.example.org", "alice", "password")
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(roomId))
		val prepared = client.prepareFileMessage(roomId)!!
		assertFalse(
			sendConnectorFile(prepared, "text/plain") {
				client.close()
				client.login("https://matrix.example.org", "bob", "password")
				ByteArrayInputStream(byteArrayOf(1))
			},
		)
		assertEquals(0, FakeMatrixSdkState.sendFileCallCount)
	}

	@Test fun nonJoinedAndEncryptedRoomsRejectFiles() {
		val prepared = connector().prepareFileSend(roomId)!!
		FakeMatrixSdkState.roomsToReturn = listOf(
			FakeRoomSpec(
				roomId,
				membership = org.matrix.rustcomponents.sdk.Membership.LEFT,
			),
		)
		assertFalse(prepared.send(byteArrayOf(1), "document", "text/plain"))
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(roomId))
		FakeMatrixSdkState.encryptionState = org.matrix.rustcomponents.sdk.EncryptionState.ENCRYPTED
		assertFalse(prepared.send(byteArrayOf(1), "document", "text/plain"))
		assertEquals(0, FakeMatrixSdkState.sendFileCallCount)
	}

	@Test fun timedOutUploadCancelsAndReleasesFileMetadata() {
		val client = ReflectiveMatrixHomeserverDiscoveryClient(discoveryTimeoutMs = 5L)
		client.login("https://matrix.example.org", "alice", "password")
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(roomId))
		FakeMatrixSdkState.joinSuspendAsynchronously = true
		FakeMatrixSdkState.joinDelayMs = 50L
		assertFalse(client.prepareFileMessage(roomId)!!.send(byteArrayOf(1), "document", "text/plain"))
		assertEquals(1, FakeMatrixSdkState.imageCancelCount)
		assertEquals(1, FakeMatrixSdkState.fileInfoDestroyCount)
		assertEquals(1, FakeMatrixSdkState.sendAttachmentJoinHandleCloseCount)
		assertTrue(FakeMatrixSdkState.joinFinished.await(2, java.util.concurrent.TimeUnit.SECONDS))
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
