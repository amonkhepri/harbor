package org.briarproject.briar.api.connector

import java.io.File

/**
 * Opt-in capability (MX-053): downloads a received file attachment into app-private temporary storage
 * on explicit user request.
 */
interface ConnectorFileDownloader {
	companion object {
		/** Provider sizes are untrusted; enforce 10 MiB limit. */
		const val MAX_FILE_BYTES = 10L * 1024 * 1024
	}

	sealed class DownloadResult {
		data class Success(val file: File) : DownloadResult()
		data class Failure(val cause: FailureCause) : DownloadResult()
	}

	enum class FailureCause {
		OVERSIZE,
		CANCELLED,
		LOGGED_OUT,
		NETWORK_ERROR,
		NOT_FOUND,
	}

	fun downloadFile(
		threadId: String,
		messageId: String,
		attachment: ConnectorAttachmentMetadata,
		destinationDir: File,
		isCancelled: () -> Boolean = { false },
	): DownloadResult
}
