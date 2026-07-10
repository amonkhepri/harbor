package org.briarproject.briar.telegram

import org.briarproject.bramble.api.crypto.SecretKey
import org.briarproject.bramble.api.db.DatabaseConfig
import java.io.File
import java.io.IOException
import java.security.SecureRandom

interface TelegramTdlibDatabaseKeyProvider {
	fun getDatabaseEncryptionKey(tdlibDirectory: File): ByteArray?
}

object NoOpTelegramTdlibDatabaseKeyProvider : TelegramTdlibDatabaseKeyProvider {
	override fun getDatabaseEncryptionKey(tdlibDirectory: File): ByteArray? = null
}

class ProtectedTelegramTdlibDatabaseKeyProvider @JvmOverloads constructor(
	private val databaseConfig: DatabaseConfig,
	private val random: SecureRandom = SecureRandom(),
) : TelegramTdlibDatabaseKeyProvider {

	@Synchronized
	override fun getDatabaseEncryptionKey(tdlibDirectory: File): ByteArray? {
		return try {
			val strengthener = databaseConfig.keyStrengthener ?: return null
			if (!strengthener.isInitialised) return null
			val seedFile = seedFile()
			val markerFile = markerFile()
			resetUnmarkedTdlibState(tdlibDirectory, seedFile, markerFile)
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

	private fun resetUnmarkedTdlibState(tdlibDirectory: File, seedFile: File, markerFile: File) {
		if (tdlibDirectory.exists() &&
			(!seedFile.isFile || seedFile.length() != SecretKey.LENGTH.toLong() || !markerFile.isFile)
		) {
			tdlibDirectory.deleteRecursively()
		}
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
		markerFile.parentFile?.mkdirs()
		if (!markerFile.isFile) markerFile.writeText(MARKER_TEXT)
	}

	private fun seedFile(): File = File(databaseConfig.databaseKeyDirectory, SEED_FILE)

	private fun markerFile(): File = File(databaseConfig.databaseKeyDirectory, MARKER_FILE)

	private companion object {
		const val SEED_FILE = "telegram-tdlib-key.seed"
		const val MARKER_FILE = "telegram-tdlib-key.marker"
		const val MARKER_TEXT = "telegram-tdlib-key-v1\n"
	}
}
