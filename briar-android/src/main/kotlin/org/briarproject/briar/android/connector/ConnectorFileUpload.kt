package org.briarproject.briar.android.connector

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.briarproject.briar.api.connector.ConnectorFileSender
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException

/** No size query is required: even unknown-size streams are bounded to 10 MiB. No temp files. */
internal fun sendConnectorFile(
	send: ConnectorFileSender.PreparedFileSend,
	mimeType: String,
	openStream: () -> InputStream?,
): Boolean {
	if (!mimeType.matches(Regex("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+"))) return false
	return try {
		val bytes = openStream()?.use { input ->
			val output = ByteArrayOutputStream()
			val buffer = ByteArray(8192)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				if (count > ConnectorFileSender.MAX_FILE_BYTES - output.size()) return false
				output.write(buffer, 0, count)
			}
			output.toByteArray()
		} ?: return false
		// A neutral name avoids collecting provider display names or leaking path components.
		bytes.isNotEmpty() && send.send(bytes, "document", mimeType)
	} catch (e: IOException) {
		false
	} catch (e: RuntimeException) {
		false
	}
}

/** Keeps the session captured when the picker opened and any in-flight result across rotation. */
internal class ConnectorFileUploadState : ViewModel() {
	enum class Status { IDLE, PICKING, SENDING, SENT, FAILED }
	val status = MutableLiveData(Status.IDLE)
	private var prepared: ConnectorFileSender.PreparedFileSend? = null

	fun pick(send: ConnectorFileSender.PreparedFileSend) {
		prepared = send
		status.value = Status.PICKING
	}

	fun choose(hasSelection: Boolean): ConnectorFileSender.PreparedFileSend? {
		val send = prepared
		prepared = null
		status.value =
			if (!hasSelection) {
				Status.IDLE
			} else if (send == null) {
				Status.FAILED
			} else {
				Status.SENDING
			}
		return if (hasSelection) send else null
	}

	fun complete(sent: Boolean) {
		status.value = if (sent) Status.SENT else Status.FAILED
	}

	fun acknowledge() {
		status.value = Status.IDLE
	}
}
