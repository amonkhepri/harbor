package org.briarproject.briar.connector

import org.briarproject.bramble.api.crypto.SecretKey
import org.briarproject.bramble.api.db.DatabaseConfig
import java.io.File
import java.io.IOException
import java.security.SecureRandom

interface ConnectorStoreKeyProvider {
	fun getStoreEncryptionKey(storeDirectory: File): ByteArray?

	fun isKeyStrengtheningAvailable(): Boolean = true
}

class ProtectedConnectorStoreKeyProvider(
	private val databaseConfig: DatabaseConfig,
	private val stateName: String,
	private val random: SecureRandom = SecureRandom(),
) : ConnectorStoreKeyProvider {

	override fun isKeyStrengtheningAvailable(): Boolean = databaseConfig.keyStrengthener != null

	@Synchronized
	override fun getStoreEncryptionKey(storeDirectory: File): ByteArray? {
		return try {
			val strengthener = databaseConfig.keyStrengthener ?: return null
			if (!strengthener.isInitialised) return null
			val seedFile = seedFile()
			val markerFile = markerFile()
			if (!resetUnmarkedStore(storeDirectory, seedFile, markerFile)) return null
			val seed = readOrCreateSeed(seedFile)
			strengthener.strengthenKey(SecretKey(seed)).bytes.copyOf().also {
				writeMarker(markerFile)
			}
		} catch (_: IOException) {
			null
		} catch (_: RuntimeException) {
			null
		}
	}

	private fun resetUnmarkedStore(storeDirectory: File, seedFile: File, markerFile: File): Boolean {
		if (storeDirectory.exists() &&
			(
				!seedFile.isFile ||
					seedFile.length() != SecretKey.LENGTH.toLong() ||
					!markerFile.isFile ||
					markerFile.readText() != markerText()
				)
		) {
			return storeDirectory.deleteRecursively()
		}
		return true
	}

	@Throws(IOException::class)
	private fun readOrCreateSeed(seedFile: File): ByteArray {
		if (seedFile.isFile && seedFile.length() == SecretKey.LENGTH.toLong()) {
			return seedFile.readBytes()
		}
		val seed = ByteArray(SecretKey.LENGTH)
		random.nextBytes(seed)
		seedFile.parentFile?.mkdirs()
		seedFile.writeBytes(seed)
		return seed
	}

	@Throws(IOException::class)
	private fun writeMarker(markerFile: File) {
		val markerText = markerText()
		markerFile.parentFile?.mkdirs()
		if (!markerFile.isFile || markerFile.readText() != markerText) {
			markerFile.writeText(markerText)
		}
	}

	private fun seedFile(): File = File(databaseConfig.databaseKeyDirectory, "$stateName.seed")

	private fun markerFile(): File = File(databaseConfig.databaseKeyDirectory, "$stateName.marker")

	private fun markerText(): String = "$stateName-v1\n"
}
