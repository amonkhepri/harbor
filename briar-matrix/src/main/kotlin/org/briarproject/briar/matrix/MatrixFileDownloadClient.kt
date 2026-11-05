package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorAttachmentMetadata
import org.briarproject.briar.api.connector.ConnectorFileDownloader.DownloadResult
import java.io.File

/**
 * Narrow boundary over downloading media via Matrix SDK (MX-053).
 */
interface MatrixFileDownloadClient {
	fun downloadFile(
		attachment: ConnectorAttachmentMetadata,
		destinationDir: File,
		isCancelled: () -> Boolean = { false },
	): DownloadResult
}
