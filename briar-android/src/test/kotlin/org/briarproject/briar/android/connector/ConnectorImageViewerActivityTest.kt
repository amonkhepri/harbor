package org.briarproject.briar.android.connector

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorImageViewerActivityTest {

	@get:Rule
	val instantTaskExecutorRule = InstantTaskExecutorRule()

	private val cacheDir: File get() = RuntimeEnvironment.getApplication().cacheDir

	@Test
	fun stagedImageFileAcceptsOnlyAttachmentStagingFiles() {
		val staged = File(cacheDir, "matrix_attachments/abc/photo.jpg")
		assertEquals(
			staged.canonicalFile,
			ConnectorImageViewerActivity.stagedImageFile(cacheDir, staged.path),
		)
		assertNull(ConnectorImageViewerActivity.stagedImageFile(cacheDir, null))
		assertNull(
			ConnectorImageViewerActivity.stagedImageFile(cacheDir, File(cacheDir, "x.jpg").path),
		)
		assertNull(
			ConnectorImageViewerActivity.stagedImageFile(
				cacheDir,
				File(cacheDir, "matrix_attachments/../secret.jpg").path,
			),
		)
		assertNull(
			ConnectorImageViewerActivity.stagedImageFile(
				cacheDir,
				File(cacheDir, "matrix_attachments").path,
			),
		)
	}

	@Test
	fun sampleSizeBoundsDecodedDimensions() {
		assertEquals(1, ConnectorImageViewerActivity.sampleSize(800, 600, 2048))
		assertEquals(2, ConnectorImageViewerActivity.sampleSize(4096, 1000, 2048))
		assertEquals(4, ConnectorImageViewerActivity.sampleSize(1000, 8000, 2048))
	}

	@Test
	fun rejectedPathFailsWithoutDecoding() {
		val viewer = ConnectorImageViewerState()
		var executed = 0
		viewer.load(null) { executed++ }
		assertSame(ImageState.Failed, viewer.image.value)
		assertEquals(0, executed)
	}

	@Test
	fun missingStagedFileFails() {
		val viewer = ConnectorImageViewerState()
		viewer.load(File(cacheDir, "matrix_attachments/gone/photo.jpg")) { it.run() }
		assertSame(ImageState.Failed, viewer.image.value)
	}

	@Test
	fun loadIsNotRepeatedAcrossRecreation() {
		val viewer = ConnectorImageViewerState()
		val pending = mutableListOf<Runnable>()
		val file = File(cacheDir, "matrix_attachments/gone/photo.jpg")
		viewer.load(file) { pending.add(it) }
		assertSame(ImageState.Loading, viewer.image.value)
		viewer.load(file) { pending.add(it) }
		assertEquals(1, pending.size)
	}
}
