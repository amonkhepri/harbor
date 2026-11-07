package org.briarproject.briar.android.connector

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.chrisbanes.photoview.PhotoView
import org.briarproject.bramble.api.lifecycle.IoExecutor
import org.briarproject.briar.R
import org.briarproject.briar.android.activity.ActivityComponent
import org.briarproject.briar.android.activity.BriarActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Shows a downloaded connector image full screen (MX-056). The image file is owned by the
 * conversation's attachment staging directory; this screen only reads it and never persists it.
 */
class ConnectorImageViewerActivity : BriarActivity() {

	companion object {
		internal const val EXTRA_IMAGE_PATH = "connector.IMAGE_PATH"
		internal const val MAX_IMAGE_DIMENSION = 2048

		internal fun intent(context: Context, file: File): Intent =
			Intent(context, ConnectorImageViewerActivity::class.java)
				.putExtra(EXTRA_IMAGE_PATH, file.absolutePath)

		/** Only files staged under the conversation attachment directory may be displayed. */
		internal fun stagedImageFile(cacheDir: File, path: String?): File? {
			if (path.isNullOrEmpty()) return null
			return try {
				val root = File(cacheDir, ConnectorConversationActivity.ATTACHMENTS_DIR_NAME)
					.canonicalFile
				val file = File(path).canonicalFile
				if (file.path.startsWith(root.path + File.separator)) file else null
			} catch (e: IOException) {
				null
			}
		}

		internal fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
			var sample = 1
			while (width / sample > maxDimension || height / sample > maxDimension) sample *= 2
			return sample
		}

		internal fun decodeImage(file: File, maxDimension: Int = MAX_IMAGE_DIMENSION): Bitmap? {
			if (!file.isFile) return null
			val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(file.path, bounds)
			if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
			val options = BitmapFactory.Options().apply {
				inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
			}
			return try {
				BitmapFactory.decodeFile(file.path, options)
			} catch (e: OutOfMemoryError) {
				null
			}
		}
	}

	@Inject
	@field:IoExecutor
	lateinit var ioExecutor: Executor

	override fun injectActivity(component: ActivityComponent) {
		component.inject(this)
	}

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setContentView(R.layout.activity_connector_image_viewer)
		// The overlay theme has no window action bar, so supply the toolbar for title and up.
		setUpCustomToolbar(false)
		val photoView: PhotoView = findViewById(R.id.connectorImageView)
		val progress: ProgressBar = findViewById(R.id.connectorImageProgress)
		val error: TextView = findViewById(R.id.connectorImageError)
		val viewer = ViewModelProvider(this)[ConnectorImageViewerState::class.java]
		viewer.image.observe(this) { image ->
			progress.visibility = if (image is ImageState.Loading) View.VISIBLE else View.GONE
			error.visibility = if (image is ImageState.Failed) View.VISIBLE else View.GONE
			photoView.setImageBitmap((image as? ImageState.Loaded)?.bitmap)
		}
		val file = stagedImageFile(cacheDir, intent.getStringExtra(EXTRA_IMAGE_PATH))
		viewer.load(file, ioExecutor)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if (item.itemId == android.R.id.home) {
			onBackPressed()
			return true
		}
		return super.onOptionsItemSelected(item)
	}
}

internal sealed class ImageState {
	object Loading : ImageState()
	object Failed : ImageState()
	class Loaded(val bitmap: Bitmap) : ImageState()
}

/** Retains the decoded image across rotation so it is decoded at most once per viewer. */
internal class ConnectorImageViewerState : ViewModel() {
	val image = MutableLiveData<ImageState>()

	@Volatile private var cleared = false

	fun load(file: File?, executor: Executor) {
		if (image.value != null) return
		if (file == null) {
			image.value = ImageState.Failed
			return
		}
		image.value = ImageState.Loading
		executor.execute {
			val bitmap = ConnectorImageViewerActivity.decodeImage(file)
			if (cleared) return@execute
			image.postValue(bitmap?.let { ImageState.Loaded(it) } ?: ImageState.Failed)
		}
	}

	override fun onCleared() {
		cleared = true
	}
}
