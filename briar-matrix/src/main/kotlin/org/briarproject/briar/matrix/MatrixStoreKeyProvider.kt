package org.briarproject.briar.matrix

import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.db.DatabaseConfig
import org.briarproject.briar.connector.ConnectorStoreKeyProvider
import org.briarproject.briar.connector.ProtectedConnectorStoreKeyProvider
import java.io.File
import java.security.SecureRandom

interface MatrixStoreKeyProvider : ConnectorStoreKeyProvider

interface MatrixStoreConfigurationProvider {
	fun getStoreConfiguration(): MatrixStoreConfiguration?
}

class MatrixStoreConfiguration(
	val directory: File,
	val databaseName: String,
	encryptionKey: ByteArray,
) {
	private val encryptionKey = encryptionKey.copyOf()

	fun copyEncryptionKey(): ByteArray = encryptionKey.copyOf()
}

class ProtectedMatrixStoreConfigurationProvider @JvmOverloads constructor(
	private val databaseConfig: DatabaseConfig,
	accountManager: AccountManager,
	private val keyProvider: MatrixStoreKeyProvider =
		ProtectedMatrixStoreKeyProvider(databaseConfig, accountManager),
) : MatrixStoreConfigurationProvider {
	override fun getStoreConfiguration(): MatrixStoreConfiguration? {
		val directory = File(databaseConfig.databaseDirectory, STORE_NAME)
		val key = keyProvider.getStoreEncryptionKey(directory) ?: return null
		return MatrixStoreConfiguration(directory, STORE_NAME, key)
	}

	private companion object {
		const val STORE_NAME = "matrix-sdk"
	}
}

class ProtectedMatrixStoreKeyProvider(
	databaseConfig: DatabaseConfig,
	accountManager: AccountManager,
	random: SecureRandom = SecureRandom(),
) : MatrixStoreKeyProvider {
	private val delegate =
		ProtectedConnectorStoreKeyProvider(databaseConfig, STATE_NAME, accountManager, random)

	override fun getStoreEncryptionKey(storeDirectory: File): ByteArray? =
		delegate.getStoreEncryptionKey(storeDirectory)

	override fun isKeyStrengtheningAvailable(): Boolean = delegate.isKeyStrengtheningAvailable()

	private companion object {
		const val STATE_NAME = "matrix-sdk-store-key"
	}
}
