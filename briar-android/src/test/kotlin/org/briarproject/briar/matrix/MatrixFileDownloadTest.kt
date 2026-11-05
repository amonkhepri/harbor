package org.briarproject.briar.matrix

import org.briarproject.bramble.test.SettableClock
import org.briarproject.briar.api.connector.ConnectorAttachmentMetadata
import org.briarproject.briar.api.connector.ConnectorFileDownloader
import org.briarproject.briar.api.connector.ConnectorFileDownloader.DownloadResult
import org.briarproject.briar.api.connector.ConnectorFileDownloader.FailureCause
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MatrixFileDownloadTest {

	private lateinit var tempDir: File

	@Before
	fun setUp() {
		FakeMatrixSdkState.reset()
		tempDir =
			File(System.getProperty("java.io.tmpdir"), "test_matrix_downloads_" + System.currentTimeMillis())
		tempDir.mkdirs()
	}

	@After
	fun tearDown() {
		tempDir.deleteRecursively()
		FakeMatrixSdkState.reset()
	}

	private fun loggedInClient(): ReflectiveMatrixHomeserverDiscoveryClient {
		val client = ReflectiveMatrixHomeserverDiscoveryClient()
		client.login("https://matrix.example.org", "alice", "swordfish")
		return client
	}

	@Test
	fun downloadDisposableFileIntoTemporaryStorageSucceeds() {
		val client = loggedInClient()
		FakeMatrixSdkState.mediaContentToReturn = "valid file data".toByteArray()

		val attachment = ConnectorAttachmentMetadata(
			fileName = "document.pdf",
			mimeType = "application/pdf",
			size = 15L,
			uri = "mxc://example.org/abc123",
			mediaSourceJson = "{\"url\":\"mxc://example.org/abc123\"}",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertTrue(result is DownloadResult.Success)
		val file = (result as DownloadResult.Success).file
		assertTrue(file.exists())
		assertEquals("valid file data", file.readText())
		assertEquals(tempDir.absolutePath, file.parentFile?.absolutePath)
	}

	@Test
	fun cancellationLeavesNoPartialAccessibleFile() {
		val client = loggedInClient()
		FakeMatrixSdkState.mediaContentToReturn = "partial content".toByteArray()

		val attachment = ConnectorAttachmentMetadata(
			fileName = "cancelled.pdf",
			mimeType = "application/pdf",
			size = 100L,
			uri = "mxc://example.org/cancel",
		)

		val result = client.downloadFile(attachment, tempDir, isCancelled = { true })
		assertEquals(DownloadResult.Failure(FailureCause.CANCELLED), result)
		val target = File(tempDir, "cancelled.pdf")
		assertFalse(target.exists())
		val children = tempDir.listFiles() ?: emptyArray()
		assertTrue(children.isEmpty())
	}

	@Test
	fun logoutLeavesNoPartialAccessibleFile() {
		val client = loggedInClient()
		client.logout()

		val attachment = ConnectorAttachmentMetadata(
			fileName = "after_logout.pdf",
			mimeType = "application/pdf",
			size = 100L,
			uri = "mxc://example.org/logout",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertEquals(DownloadResult.Failure(FailureCause.LOGGED_OUT), result)
		val target = File(tempDir, "after_logout.pdf")
		assertFalse(target.exists())
	}

	@Test
	fun declaredOversizeFailsClosedWithoutCreatingFile() {
		val client = loggedInClient()
		val attachment = ConnectorAttachmentMetadata(
			fileName = "giant.zip",
			mimeType = "application/zip",
			size = ConnectorFileDownloader.MAX_FILE_BYTES + 1L,
			uri = "mxc://example.org/giant",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertEquals(DownloadResult.Failure(FailureCause.OVERSIZE), result)
		assertEquals(0, FakeMatrixSdkState.getMediaFileCallCount)
		assertFalse(File(tempDir, "giant.zip").exists())
	}

	@Test
	fun downloadedOversizeCleansUpAndLeavesNoPartialFile() {
		val client = loggedInClient()
		val oversizedBytes = ByteArray((ConnectorFileDownloader.MAX_FILE_BYTES + 1024L).toInt())
		FakeMatrixSdkState.mediaContentToReturn = oversizedBytes

		val attachment = ConnectorAttachmentMetadata(
			fileName = "oversized.bin",
			mimeType = "application/octet-stream",
			size = 100L, // declared small but downloaded large
			uri = "mxc://example.org/oversized",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertEquals(DownloadResult.Failure(FailureCause.OVERSIZE), result)
		assertFalse(File(tempDir, "oversized.bin").exists())
		val children = tempDir.listFiles() ?: emptyArray()
		assertTrue(children.isEmpty())
	}

	@Test
	fun networkFailureCleansUpAndLeavesNoPartialFile() {
		val client = loggedInClient()
		FakeMatrixSdkState.getMediaFileException = ClientException.Generic("network failed", "timeout")

		val attachment = ConnectorAttachmentMetadata(
			fileName = "network_fail.pdf",
			mimeType = "application/pdf",
			size = 100L,
			uri = "mxc://example.org/fail",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertEquals(DownloadResult.Failure(FailureCause.NETWORK_ERROR), result)
		assertFalse(File(tempDir, "network_fail.pdf").exists())
	}

	@Test
	fun connectorDownloadFileRequiresAuthorization() {
		val authSession = FakeMatrixAuthSession(MatrixAuthState.CREDENTIAL_ENTRY)
		val discoveryClient = ReflectiveMatrixHomeserverDiscoveryClient()
		val connector = MatrixRoomConnector(
			roomSnapshotClient = discoveryClient,
			authSession = authSession,
			messageSnapshotClient = discoveryClient,
			messageSendClient = discoveryClient,
			clock = SettableClock(AtomicLong(0L)),
			fileDownloadClient = discoveryClient,
		)

		val attachment = ConnectorAttachmentMetadata(
			fileName = "auth_test.pdf",
			mimeType = "application/pdf",
			size = 100L,
			uri = "mxc://example.org/auth",
		)

		// Not authorized -> LOGGED_OUT
		val unauthResult = connector.downloadFile("!room", "\$msg", attachment, tempDir)
		assertEquals(DownloadResult.Failure(FailureCause.LOGGED_OUT), unauthResult)

		// Authorize and login -> success
		discoveryClient.login("https://matrix.example.org", "alice", "swordfish")
		authSession.state = MatrixAuthState.READY
		FakeMatrixSdkState.mediaContentToReturn = "authed file".toByteArray()

		val authResult = connector.downloadFile("!room", "\$msg", attachment, tempDir)
		assertTrue(authResult is DownloadResult.Success)
		val file = (authResult as DownloadResult.Success).file
		assertTrue(file.exists())
		assertEquals("authed file", file.readText())
	}

	private class FakeMatrixAuthSession(var state: MatrixAuthState) : MatrixAuthSession {
		override fun getSnapshot(): MatrixAuthSession.Snapshot =
			MatrixAuthSession.Snapshot(state, MatrixAuthSession.RecoverableErrorDetail.NONE)
		override fun start() {}
		override fun submitHomeserver(homeserverUrl: String) {}
		override fun submitCredentials(username: String, password: String) {}
		override fun logout() {}
		override fun close() {}
	}
}
