package org.briarproject.briar.matrix

import org.briarproject.bramble.api.db.DatabaseConfig
import org.briarproject.briar.connector.ConnectorStoreKeyProvider
import org.briarproject.briar.connector.ProtectedConnectorStoreKeyProvider
import java.io.File
import java.security.SecureRandom

interface MatrixStoreKeyProvider : ConnectorStoreKeyProvider

class ProtectedMatrixStoreKeyProvider(
	databaseConfig: DatabaseConfig,
	random: SecureRandom = SecureRandom(),
) : MatrixStoreKeyProvider {
	private val delegate = ProtectedConnectorStoreKeyProvider(databaseConfig, STATE_NAME, random)

	override fun getStoreEncryptionKey(storeDirectory: File): ByteArray? =
		delegate.getStoreEncryptionKey(storeDirectory)

	override fun isKeyStrengtheningAvailable(): Boolean = delegate.isKeyStrengtheningAvailable()

	private companion object {
		const val STATE_NAME = "matrix-sdk-store-key"
	}
}
