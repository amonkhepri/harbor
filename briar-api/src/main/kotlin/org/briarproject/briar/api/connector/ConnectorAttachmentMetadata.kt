package org.briarproject.briar.api.connector

data class ConnectorAttachmentMetadata(
	val fileName: String? = null,
	val mimeType: String? = null,
	val size: Long? = null,
	val uri: String? = null,
	val mediaSourceJson: String? = null,
	val thumbnailUri: String? = null,
	val thumbnailSourceJson: String? = null,
)
