package org.briarproject.briar.connector

import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.crypto.SecretKey
import org.briarproject.bramble.api.db.DatabaseConfig
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface ConnectorStoreKeyProvider {
	fun getStoreEncryptionKey(storeDirectory: File): ByteArray?

	fun isKeyStrengtheningAvailable(): Boolean = true
}

/**
 * Derives a store encryption key from a device-resident random seed
 * strengthened via [org.briarproject.bramble.api.crypto.KeyStrengthener], the
 * same way as Briar's own database key. Unlike Briar's own database key, the
 * seed alone never required the user's Briar login password. Binding the
 * derivation to [AccountManager.getDatabaseKey] closes that gap: that key is
 * only available in memory after a successful password-based sign-in, so
 * reading the seed and marker files from disk is no longer sufficient to
 * reconstruct the store key.
 */
class ProtectedConnectorStoreKeyProvider(
	private val databaseConfig: DatabaseConfig,
	private val stateName: String,
	private val accountManager: AccountManager,
	private val random: SecureRandom = SecureRandom(),
) : ConnectorStoreKeyProvider {

	override fun isKeyStrengtheningAvailable(): Boolean = databaseConfig.keyStrengthener != null

	@Synchronized
	override fun getStoreEncryptionKey(storeDirectory: File): ByteArray? {
		return try {
			val strengthener = databaseConfig.keyStrengthener ?: return null
			if (!strengthener.isInitialised) return null
			val databaseKey = accountManager.databaseKey ?: return null
			val seedFile = seedFile()
			val markerFile = markerFile()
			if (!resetUnmarkedStore(storeDirectory, seedFile, markerFile)) return null
			val seed = readOrCreateSeed(seedFile)
			val boundSeed = bindToDatabaseKey(seed, databaseKey)
			strengthener.strengthenKey(SecretKey(boundSeed)).bytes.copyOf().also {
				writeMarker(markerFile)
			}
		} catch (_: IOException) {
			null
		} catch (_: RuntimeException) {
			null
		}
	}

	/**
	 * Combines the device-resident seed with Briar's password-derived
	 * database key via HMAC-SHA256, so the resulting key cannot be
	 * reconstructed from the seed file and Keystore alone.
	 */
	private fun bindToDatabaseKey(seed: ByteArray, databaseKey: SecretKey): ByteArray {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(databaseKey.bytes, "HmacSHA256"))
		return mac.doFinal(seed)
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

	// v2: the derived key is now bound to AccountManager.getDatabaseKey() (see
	// bindToDatabaseKey); bumping the version forces a one-time reset of any
	// pre-existing v1 store so it isn't opened with a now-unreconstructable key.
	private fun markerText(): String = "$stateName-v2\n"
}
