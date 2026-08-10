package org.briarproject.briar.matrix

import org.briarproject.bramble.api.crypto.KeyStrengthener
import org.briarproject.bramble.api.crypto.SecretKey
import org.briarproject.bramble.api.db.DatabaseConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MatrixStoreKeyProviderTest {

	@get:Rule
	val testFolder = TemporaryFolder()

	@Test
	fun testResetsUnmarkedStoreAndReusesStrengthenedKey() {
		val keyDirectory = testFolder.newFolder("key")
		val provider = ProtectedMatrixStoreKeyProvider(config(keyDirectory, FakeKeyStrengthener()))
		val storeDirectory = testFolder.newFolder("matrix")
		val staleState = File(storeDirectory, "store/stale").also {
			it.parentFile?.mkdirs()
			it.writeText("stale")
		}

		val firstKey = checkNotNull(provider.getStoreEncryptionKey(storeDirectory))
		val retainedState = File(storeDirectory, "store/retained").also {
			it.parentFile?.mkdirs()
			it.writeText("retained")
		}
		val secondKey = checkNotNull(provider.getStoreEncryptionKey(storeDirectory))

		assertEquals(false, staleState.exists())
		assertEquals(true, retainedState.exists())
		assertEquals(true, provider.isKeyStrengtheningAvailable())
		assertEquals(SecretKey.LENGTH, firstKey.size)
		assertArrayEquals(firstKey, secondKey)
		assertEquals(true, File(keyDirectory, "matrix-sdk-store-key.seed").isFile)
		assertEquals(true, File(keyDirectory, "matrix-sdk-store-key.marker").isFile)
	}

	@Test
	fun testBuildsAccountScopedStoreConfigurationWithIsolatedKey() {
		val databaseDirectory = testFolder.newFolder("database")
		val expectedKey = ByteArray(SecretKey.LENGTH) { it.toByte() }
		val sourceKey = expectedKey.copyOf()
		var requestedStoreDirectory: File? = null
		val provider = ProtectedMatrixStoreConfigurationProvider(
			config(databaseDirectory = databaseDirectory),
			keyProvider(sourceKey) { requestedStoreDirectory = it },
		)

		val configuration = checkNotNull(provider.getStoreConfiguration())
		sourceKey[0] = (sourceKey[0].toInt() xor 0x7F).toByte()
		val copiedKey = configuration.copyEncryptionKey()

		assertEquals(File(databaseDirectory, "matrix-sdk"), configuration.directory)
		assertEquals(configuration.directory, requestedStoreDirectory)
		assertEquals("matrix-sdk", configuration.databaseName)
		assertArrayEquals(expectedKey, copiedKey)
		copiedKey[0] = (copiedKey[0].toInt() xor 0x7F).toByte()
		assertArrayEquals(expectedKey, configuration.copyEncryptionKey())
	}

	@Test
	fun testReturnsNoStoreConfigurationWhenProtectedKeyIsUnavailable() {
		val provider = ProtectedMatrixStoreConfigurationProvider(
			config(),
			keyProvider(null),
		)

		assertEquals(null, provider.getStoreConfiguration())
	}

	private fun config(
		keyDirectory: File = testFolder.root,
		strengthener: KeyStrengthener = FakeKeyStrengthener(),
		databaseDirectory: File = testFolder.root,
	) = object : DatabaseConfig {
		override fun getDatabaseDirectory(): File = databaseDirectory
		override fun getDatabaseKeyDirectory(): File = keyDirectory
		override fun getKeyStrengthener(): KeyStrengthener = strengthener
	}

	private fun keyProvider(key: ByteArray?, onStoreDirectory: (File) -> Unit = {}) =
		object : MatrixStoreKeyProvider {
			override fun getStoreEncryptionKey(storeDirectory: File): ByteArray? {
				onStoreDirectory(storeDirectory)
				return key
			}
		}

	private class FakeKeyStrengthener : KeyStrengthener {
		override fun isInitialised(): Boolean = true

		override fun strengthenKey(k: SecretKey): SecretKey = SecretKey(
			k.bytes.mapIndexed { index, byte ->
				(byte.toInt() xor index xor 0x5A).toByte()
			}.toByteArray(),
		)
	}
}
