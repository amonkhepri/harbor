package org.briarproject.briar.matrix

import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.db.DatabaseConfig
import org.briarproject.briar.connector.ConnectorStoreKeyProvider
import org.briarproject.briar.connector.ProtectedConnectorStoreKeyProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.security.SecureRandom

interface MatrixStoreKeyProvider : ConnectorStoreKeyProvider

interface MatrixStoreConfigurationProvider {
	fun getStoreConfiguration(): MatrixStoreConfiguration?
	fun newStoreConfiguration(): MatrixStoreConfiguration? = getStoreConfiguration()
	fun invalidateStore() = Unit
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
	// Selection is outside SDK-owned directories. An empty selection is a durable logout
	// tombstone, including for legacy stores. Never delete a possibly live native store.
	private val selection get() = File(databaseConfig.databaseDirectory, "matrix-active-store")

	init {
		// Safe only here: this constructor runs once per process, before this process has
		// claimed (opened a native client against) any store directory, so no directory this
		// process could see is a possibly-live native store yet. A directory left behind by a
		// prior process (superseded login attempt, or a logout that only cleared the
		// selection) is guaranteed quiescent because that process has since exited.
		pruneOrphanedStores()
	}

	override fun getStoreConfiguration(): MatrixStoreConfiguration? = synchronized(ownershipLock) {
		try {
			val name = if (selection.exists()) {
				selection.readText()
			} else {
				val legacy = File(databaseConfig.databaseDirectory, STORE_NAME)
				val initial = if (File(legacy, "session").isFile) STORE_NAME else freshName()
				select(initial)
				initial
			}
			if (name.isEmpty()) return@synchronized null
			if (name != STORE_NAME && !name.matches(FRESH_STORE_NAME_REGEX)) {
				return@synchronized null
			}
			configuration(name)
		} catch (_: IOException) {
			null
		}
	}

	override fun newStoreConfiguration(): MatrixStoreConfiguration? = synchronized(ownershipLock) {
		try {
			val name = freshName()
			val configuration = configuration(name) ?: return@synchronized null
			select(name)
			configuration
		} catch (_: IOException) {
			null
		}
	}

	override fun invalidateStore() = synchronized(ownershipLock) { select("") }

	private fun configuration(name: String): MatrixStoreConfiguration? {
		val directory = File(databaseConfig.databaseDirectory, name)
		val key = keyProvider.getStoreEncryptionKey(directory) ?: return null
		return MatrixStoreConfiguration(directory, STORE_NAME, key)
	}

	private fun freshName() = "matrix-sdk-${UUID.randomUUID()}"

	private fun pruneOrphanedStores() = synchronized(ownershipLock) {
		try {
			val keep = if (selection.exists()) selection.readText() else null
			databaseConfig.databaseDirectory.listFiles { file ->
				file.isDirectory && file.name != keep && file.name.matches(FRESH_STORE_NAME_REGEX)
			}?.forEach { it.deleteRecursively() }
		} catch (_: Exception) {
			// Best-effort disk reclamation; a directory left behind is retried on next start.
		}
	}

	private fun select(name: String) {
		selection.parentFile?.mkdirs()
		val temp = File.createTempFile("matrix-selection-", ".tmp", selection.parentFile)
		try {
			FileOutputStream(temp).use {
				it.write(name.toByteArray())
				it.fd.sync()
			}
			if (!temp.renameTo(selection)) throw IOException("Cannot persist Matrix store selection")
		} finally {
			temp.delete()
		}
	}

	private companion object {
		const val STORE_NAME = "matrix-sdk"
		val ownershipLock = Any()
		val FRESH_STORE_NAME_REGEX = Regex("matrix-sdk-[0-9a-f-]{36}")
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
