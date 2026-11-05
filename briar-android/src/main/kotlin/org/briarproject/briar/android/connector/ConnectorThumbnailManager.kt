package org.briarproject.briar.android.connector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.collection.LruCache
import org.briarproject.briar.api.connector.ConnectorImageSender
import org.briarproject.briar.api.connector.ConnectorThumbnailLoader
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future

fun interface Cancellable {
	fun cancel()
}

class ConnectorThumbnailManager(
	private val thumbnailLoader: ConnectorThumbnailLoader,
	private val executor: Executor = Executors.newFixedThreadPool(2),
	private val mainHandler: Handler = Handler(Looper.getMainLooper()),
	maxCacheSizeBytes: Int = DEFAULT_CACHE_SIZE_BYTES,
) {
	private val cache = object : LruCache<String, Bitmap>(maxCacheSizeBytes) {
		override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
	}

	fun getCached(threadId: String, messageId: String): Bitmap? =
		synchronized(cache) { cache.get(cacheKey(threadId, messageId)) }

	fun loadThumbnail(
		item: ConnectorConversationMessageItem,
		maxWidth: Int = DEFAULT_MAX_DIMENSION,
		maxHeight: Int = DEFAULT_MAX_DIMENSION,
		onLoaded: (Bitmap?) -> Unit,
	): Cancellable {
		val key = cacheKey(item.connectorThreadId, item.connectorMessageId)
		val cached = synchronized(cache) { cache.get(key) }
		if (cached != null) {
			onLoaded(cached)
			return Cancellable {}
		}

		var cancelled = false
		var future: Future<*>? = null

		val task = Runnable {
			if (cancelled) return@Runnable
			val rawBytes = try {
				thumbnailLoader.loadThumbnail(
					item.connectorThreadId,
					item.connectorMessageId,
					item.attachment?.uri,
					maxWidth,
					maxHeight,
				)
			} catch (t: Throwable) {
				null
			}
			if (cancelled) return@Runnable
			val bitmap = if (rawBytes != null && rawBytes.isNotEmpty()) {
				decodeBoundedBitmap(rawBytes, maxWidth, maxHeight)
			} else {
				null
			}
			if (bitmap != null && !cancelled) {
				synchronized(cache) {
					cache.put(key, bitmap)
				}
			}
			if (!cancelled) {
				if (Looper.myLooper() == Looper.getMainLooper()) {
					onLoaded(bitmap)
				} else {
					mainHandler.post {
						if (!cancelled) {
							onLoaded(bitmap)
						}
					}
				}
			}
		}

		if (executor is java.util.concurrent.ExecutorService) {
			future = executor.submit(task)
		} else {
			executor.execute(task)
		}

		return Cancellable {
			cancelled = true
			future?.cancel(true)
		}
	}

	fun clear() {
		synchronized(cache) {
			cache.evictAll()
		}
	}

	companion object {
		const val DEFAULT_MAX_DIMENSION: Int = 480
		const val MAX_IMAGE_BYTES: Int = ConnectorImageSender.MAX_IMAGE_BYTES
		const val DEFAULT_CACHE_SIZE_BYTES: Int = 8 * 1024 * 1024

		fun cacheKey(threadId: String, messageId: String): String = "$threadId:$messageId"

		fun hasImageHeader(bytes: ByteArray): Boolean {
			if (bytes.size < 4) return false
			// JPEG: FF D8
			if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
			// PNG: 89 50 4E 47
			if (bytes[0] == 0x89.toByte() &&
				bytes[1] == 0x50.toByte() &&
				bytes[2] == 0x4E.toByte() &&
				bytes[3] == 0x47.toByte()
			) {
				return true
			}
			// GIF: 47 49 46 38 ('GIF8')
			if (bytes[0] == 0x47.toByte() &&
				bytes[1] == 0x49.toByte() &&
				bytes[2] == 0x46.toByte() &&
				bytes[3] == 0x38.toByte()
			) {
				return true
			}
			// WebP: RIFF....WEBP
			if (bytes.size >= 12 &&
				bytes[0] == 0x52.toByte() &&
				bytes[1] == 0x49.toByte() &&
				bytes[2] == 0x46.toByte() &&
				bytes[3] == 0x46.toByte() &&
				bytes[8] == 0x57.toByte() &&
				bytes[9] == 0x45.toByte() &&
				bytes[10] == 0x42.toByte() &&
				bytes[11] == 0x50.toByte()
			) {
				return true
			}
			return false
		}

		fun decodeBoundedBitmap(
			bytes: ByteArray,
			maxWidth: Int = DEFAULT_MAX_DIMENSION,
			maxHeight: Int = DEFAULT_MAX_DIMENSION,
		): Bitmap? {
			if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES || !hasImageHeader(bytes)) return null
			return try {
				val boundsOptions = BitmapFactory.Options().apply {
					inJustDecodeBounds = true
				}
				BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
				val origWidth = boundsOptions.outWidth
				val origHeight = boundsOptions.outHeight
				if (origWidth <= 0 || origHeight <= 0) return null

				var inSampleSize = 1
				while (origWidth / (inSampleSize * 2) >= maxWidth &&
					origHeight / (inSampleSize * 2) >= maxHeight
				) {
					inSampleSize *= 2
				}

				val decodeOptions = BitmapFactory.Options().apply {
					this.inSampleSize = inSampleSize
					inPreferredConfig = Bitmap.Config.RGB_565
				}
				BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
			} catch (t: Throwable) {
				null
			}
		}
	}
}
