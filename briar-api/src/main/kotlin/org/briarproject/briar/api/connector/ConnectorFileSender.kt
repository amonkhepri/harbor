package org.briarproject.briar.api.connector

/** Opt-in single-document sending. A prepared send is bound to the current room and session. */
interface ConnectorFileSender {
	companion object {
		/** Provider sizes are untrusted; enforce this 10 MiB cap while reading the stream. */
		const val MAX_FILE_BYTES = 10 * 1024 * 1024
	}

	/** Capture before opening the provider. Null means sending is unavailable. */
	fun prepareFileSend(threadId: String): PreparedFileSend?

	fun interface PreparedFileSend {
		/** Await completion; fail closed if the captured session is no longer current. */
		fun send(bytes: ByteArray, fileName: String, mimeType: String): Boolean
	}
}
