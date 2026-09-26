package org.briarproject.briar.android.connector

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.briarproject.briar.api.connector.ConnectorImageSender
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most the supported image size, even when a provider reports no size or lies about it. */
internal fun sendConnectorImage(
	sender: ConnectorImageSender,
	threadId: String,
	mimeType: String,
	fileName: String,
	openStream: () -> InputStream?,
): Boolean {
	if (!mimeType.startsWith("image/")) return false
	val bytes = openStream()?.use { input ->
		val output = ByteArrayOutputStream()
		val buffer = ByteArray(8192)
		while (true) {
			val count = input.read(buffer)
			if (count < 0) break
			if (count > ConnectorImageSender.MAX_IMAGE_BYTES - output.size()) return false
			output.write(buffer, 0, count)
		}
		output.toByteArray()
	} ?: return false
	if (bytes.isEmpty()) return false
	return sender.sendImage(threadId, bytes, fileName, mimeType)
}

/** Retains a failed selection and an in-flight upload across conversation recreation. */
internal class ConnectorImageUploadState : ViewModel() {
	enum class Status { IDLE, SENDING, SENT, FAILED }
	val status = MutableLiveData(Status.IDLE)
	var pendingImage: String? = null
		private set

	fun begin(selection: String): Boolean {
		if (status.value == Status.SENDING) return false
		pendingImage = selection
		status.value = Status.SENDING
		return true
	}

	fun complete(sent: Boolean) {
		if (sent) pendingImage = null
		status.value = if (sent) Status.SENT else Status.FAILED
	}

	fun restore(selection: String?) {
		if (status.value != Status.IDLE || selection == null) return
		pendingImage = selection
		status.value = Status.FAILED
	}
}
