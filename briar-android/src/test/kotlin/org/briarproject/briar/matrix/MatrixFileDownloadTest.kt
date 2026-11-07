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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
	fun logoutDuringDownloadAbortsAndLeavesNoPartialAccessibleFile() {
		val client = loggedInClient()
		FakeMatrixSdkState.onGetMediaFile = {
			client.logout()
		}

		val attachment = ConnectorAttachmentMetadata(
			fileName = "during_logout.pdf",
			mimeType = "application/pdf",
			size = 100L,
			uri = "mxc://example.org/midlogout",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertEquals(DownloadResult.Failure(FailureCause.LOGGED_OUT), result)
		val children = tempDir.listFiles() ?: emptyArray()
		assertTrue(children.isEmpty())
	}

	@Test
	fun cancellationDuringDownloadCleansUpAndReturnsCancelled() {
		val client = loggedInClient()
		val cancelFlag = AtomicBoolean(false)
		FakeMatrixSdkState.onGetMediaFile = {
			cancelFlag.set(true)
		}

		val attachment = ConnectorAttachmentMetadata(
			fileName = "mid_cancel.pdf",
			mimeType = "application/pdf",
			size = 100L,
			uri = "mxc://example.org/midcancel",
		)

		val result = client.downloadFile(attachment, tempDir, isCancelled = { cancelFlag.get() })
		assertEquals(DownloadResult.Failure(FailureCause.CANCELLED), result)
		val children = tempDir.listFiles() ?: emptyArray()
		assertTrue(children.isEmpty())
		assertTrue(FakeMatrixSdkState.downloadJobCancelled)
	}

	@Test
	fun hostilePathTraversalFileNameIsSanitizedAndContainedWithinTempDir() {
		val client = loggedInClient()
		FakeMatrixSdkState.mediaContentToReturn = "safe data".toByteArray()

		val attachment = ConnectorAttachmentMetadata(
			fileName = "../../hostile.txt",
			mimeType = "text/plain",
			size = 9L,
			uri = "mxc://example.org/hostile",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertTrue(result is DownloadResult.Success)
		val file = (result as DownloadResult.Success).file
		assertTrue(file.canonicalPath.startsWith(tempDir.canonicalPath))
		assertTrue(file.name.endsWith("hostile.txt"))
		assertEquals("safe data", file.readText())
	}

	@Test
	fun hostileAbsolutePathFileNameIsSanitizedAndContainedWithinTempDir() {
		val client = loggedInClient()
		FakeMatrixSdkState.mediaContentToReturn = "root data".toByteArray()

		val attachment = ConnectorAttachmentMetadata(
			fileName = "/etc/passwd",
			mimeType = "text/plain",
			size = 9L,
			uri = "mxc://example.org/rootpath",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertTrue(result is DownloadResult.Success)
		val file = (result as DownloadResult.Success).file
		assertTrue(file.canonicalPath.startsWith(tempDir.canonicalPath))
		assertTrue(file.name.endsWith("passwd"))
		assertEquals("root data", file.readText())
	}

	@Test
	fun handleTempDirIsCleanedUpLeavingPersistedFileDirectlyInDestinationDir() {
		val client = loggedInClient()
		FakeMatrixSdkState.mediaContentToReturn = "persisted content".toByteArray()

		val attachment = ConnectorAttachmentMetadata(
			fileName = "report.pdf",
			mimeType = "application/pdf",
			size = 17L,
			uri = "mxc://example.org/report",
		)

		val result = client.downloadFile(attachment, tempDir)
		assertTrue(result is DownloadResult.Success)
		val file = (result as DownloadResult.Success).file
		assertTrue(file.exists())
		assertEquals("persisted content", file.readText())
		assertEquals(tempDir.absolutePath, file.parentFile?.absolutePath)

		val files = tempDir.listFiles() ?: emptyArray()
		assertEquals(1, files.size)
		assertEquals(file.absolutePath, files[0].absolutePath)
		assertFalse(files[0].isDirectory)
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
	fun missingOrInvalidSizeOversizeFailsClosedWithoutCreatingFile() {
		val client = loggedInClient()
		val nullSizeAttachment = ConnectorAttachmentMetadata(
			fileName = "unknown.zip",
			mimeType = "application/zip",
			size = null,
			uri = "mxc://example.org/unknown",
		)
		val nullResult = client.downloadFile(nullSizeAttachment, tempDir)
		assertEquals(DownloadResult.Failure(FailureCause.OVERSIZE), nullResult)
		assertEquals(0, FakeMatrixSdkState.getMediaFileCallCount)

		val negativeSizeAttachment = ConnectorAttachmentMetadata(
			fileName = "invalid.zip",
			mimeType = "application/zip",
			size = -10L,
			uri = "mxc://example.org/invalid",
		)
		val negResult = client.downloadFile(negativeSizeAttachment, tempDir)
		assertEquals(DownloadResult.Failure(FailureCause.OVERSIZE), negResult)
		assertEquals(0, FakeMatrixSdkState.getMediaFileCallCount)
	}

	@Test
	fun logoutCleansUpDestinationDirectory() {
		val client = loggedInClient()
		FakeMatrixSdkState.mediaContentToReturn = "disposable".toByteArray()
		val attachment = ConnectorAttachmentMetadata(
			fileName = "disposable.bin",
			mimeType = "application/octet-stream",
			size = 10L,
			uri = "mxc://example.org/disp",
		)
		val result = client.downloadFile(attachment, tempDir)
		assertTrue(result is DownloadResult.Success)
		val file = (result as DownloadResult.Success).file
		assertTrue(file.exists())

		client.logout()
		assertFalse(file.exists())
		assertFalse(tempDir.exists())
	}

	@Test
	fun logoutAfterSessionRestoreCleansUpPersistedAttachmentDirectory() {
		val storeDir = File(tempDir, "store_" + UUID.randomUUID())
		storeDir.mkdirs()
		val store = MatrixStoreConfiguration(storeDir, "matrix-sdk", ByteArray(32) { it.toByte() })
		val client1 = ReflectiveMatrixHomeserverDiscoveryClient()
		assertEquals(
			MatrixAuthSession.RecoverableErrorDetail.NONE,
			client1.login("https://matrix.example.org", "alice", "swordfish", store),
		)

		val attachmentDir = File(tempDir, "attachments_" + UUID.randomUUID())
		attachmentDir.mkdirs()
		FakeMatrixSdkState.mediaContentToReturn = "persisted attachment data".toByteArray()
		val attachment = ConnectorAttachmentMetadata(
			fileName = "persisted.bin",
			mimeType = "application/octet-stream",
			size = 25L,
			uri = "mxc://example.org/persisted",
		)
		val result = client1.downloadFile(attachment, attachmentDir)
		assertTrue(result is DownloadResult.Success)
		val file = (result as DownloadResult.Success).file
		assertTrue(file.exists())

		// Simulate process death: client1 closed without logout; in-memory sets cleared
		client1.close()

		// A new client in a subsequent process restores the session from a copied cold store
		val coldStoreDir = File(tempDir, "cold_store_" + UUID.randomUUID())
		storeDir.copyRecursively(coldStoreDir, overwrite = true)
		val coldStore =
			MatrixStoreConfiguration(coldStoreDir, store.databaseName, store.copyEncryptionKey())

		val client2 = ReflectiveMatrixHomeserverDiscoveryClient()
		val restoreResult = client2.restore(coldStore)
		assertTrue(
			"Restore failed: $restoreResult",
			restoreResult is MatrixLoginClient.RestoreResult.Restored,
		)

		// Logout in restored session sweeps durably recorded attachment directory
		client2.logout()
		assertFalse(file.exists())
		assertFalse(attachmentDir.exists())
	}

	@Test
	fun mediaSourceStaticMethodsFallbackWorksWithoutCompanion() {
		val client = loggedInClient()
		val createMediaSourceMethod = ReflectiveMatrixHomeserverDiscoveryClient::class.java
			.getDeclaredMethod(
				"createMediaSource",
				Class::class.java,
				String::class.java,
				String::class.java,
			).apply { isAccessible = true }

		val result = createMediaSourceMethod.invoke(
			client,
			Class.forName("org.briarproject.briar.matrix.StaticTestMediaSourceFactoryKt"),
			null,
			"mxc://example.org/static",
		)
		assertEquals("source:mxc://example.org/static", result)
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

	@Test
	fun sequentialDownloadsInSameDirectoryDoNotTriggerFalseOversize() {
		val client = loggedInClient()
		val sixMib = 6 * 1024 * 1024
		val fiveMib = 5 * 1024 * 1024

		// File 1: 6 MiB (valid, under 10 MiB limit)
		FakeMatrixSdkState.mediaContentToReturn = ByteArray(sixMib) { 1 }
		val attachment1 = ConnectorAttachmentMetadata(
			fileName = "first_6mb.bin",
			mimeType = "application/octet-stream",
			size = sixMib.toLong(),
			uri = "mxc://example.org/first6mb",
		)
		val result1 = client.downloadFile(attachment1, tempDir)
		assertTrue(result1 is DownloadResult.Success)
		val file1 = (result1 as DownloadResult.Success).file
		assertTrue(file1.exists())
		assertEquals(sixMib.toLong(), file1.length())

		// File 2: 5 MiB (valid, under 10 MiB limit; previously would fail because 6MB + 5MB > 10MB)
		FakeMatrixSdkState.mediaContentToReturn = ByteArray(fiveMib) { 2 }
		val attachment2 = ConnectorAttachmentMetadata(
			fileName = "second_5mb.bin",
			mimeType = "application/octet-stream",
			size = fiveMib.toLong(),
			uri = "mxc://example.org/second5mb",
		)
		val result2 = client.downloadFile(attachment2, tempDir)
		assertTrue(result2 is DownloadResult.Success)
		val file2 = (result2 as DownloadResult.Success).file
		assertTrue(file2.exists())
		assertEquals(fiveMib.toLong(), file2.length())

		// Both files exist and neither was corrupted or removed
		assertTrue(file1.exists())
		assertTrue(file2.exists())
	}

	@Test
	fun cancelledDownloadCleansUpOnlyItsOwnStagingDirWithoutAffectingOtherFiles() {
		val client = loggedInClient()
		FakeMatrixSdkState.mediaContentToReturn = "file1 data".toByteArray()

		// First file downloads successfully
		val attachment1 = ConnectorAttachmentMetadata(
			fileName = "stable.pdf",
			mimeType = "application/pdf",
			size = 10L,
			uri = "mxc://example.org/stable",
		)
		val result1 = client.downloadFile(attachment1, tempDir)
		assertTrue(result1 is DownloadResult.Success)
		val file1 = (result1 as DownloadResult.Success).file
		assertTrue(file1.exists())

		// Second download is cancelled
		val attachment2 = ConnectorAttachmentMetadata(
			fileName = "cancel_me.pdf",
			mimeType = "application/pdf",
			size = 10L,
			uri = "mxc://example.org/cancel_me",
		)
		val result2 = client.downloadFile(attachment2, tempDir, isCancelled = { true })
		assertEquals(DownloadResult.Failure(FailureCause.CANCELLED), result2)

		// File 1 is still present and intact
		assertTrue(file1.exists())
		assertEquals("file1 data", file1.readText())
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
