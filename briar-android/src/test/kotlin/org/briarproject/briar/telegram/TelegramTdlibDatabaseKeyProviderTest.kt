package org.briarproject.briar.telegram

import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.crypto.KeyStrengthener
import org.briarproject.bramble.api.crypto.SecretKey
import org.briarproject.bramble.api.db.DatabaseConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelegramTdlibDatabaseKeyProviderTest {

	@get:Rule
	val testFolder = TemporaryFolder()

	@Test
	fun testResetsUnmarkedTdlibStateAndReusesKey() {
		val provider = provider(FakeKeyStrengthener())
		val tdlibDirectory = testFolder.newFolder("tdlib")
		val oldState = File(tdlibDirectory, "database/plain").also {
			it.parentFile?.mkdirs()
			it.writeText("old")
		}

		val firstKey = checkNotNull(provider.getDatabaseEncryptionKey(tdlibDirectory))
		val secondKey = checkNotNull(provider.getDatabaseEncryptionKey(tdlibDirectory))

		assertEquals(false, oldState.exists())
		assertEquals(true, provider.isKeyStrengtheningAvailable())
		assertEquals(SecretKey.LENGTH, firstKey.size)
		assertArrayEquals(firstKey, secondKey)
	}

	@Test
	fun testReturnsNoKeyWithoutDatabaseKey() {
		val provider = provider(FakeKeyStrengthener(), accountManager = fakeAccountManager(null))

		assertEquals(null, provider.getDatabaseEncryptionKey(testFolder.newFolder("tdlib")))
	}

	@Test
	fun testKeyIsBoundToTheDatabaseKey() {
		val keyDirectory = testFolder.newFolder("key")
		val firstProvider = provider(FakeKeyStrengthener(), keyDirectory = keyDirectory)
		val tdlibDirectory1 = testFolder.newFolder("tdlib1")
		val boundKey = checkNotNull(firstProvider.getDatabaseEncryptionKey(tdlibDirectory1))

		val secondProvider = provider(
			FakeKeyStrengthener(),
			keyDirectory = keyDirectory,
			accountManager = fakeAccountManager(SecretKey(ByteArray(SecretKey.LENGTH) { 1 })),
		)
		val tdlibDirectory2 = testFolder.newFolder("tdlib2")
		val differentlyBoundKey = checkNotNull(secondProvider.getDatabaseEncryptionKey(tdlibDirectory2))

		// Same on-disk seed, different account database keys, must not derive the same store key.
		assertNotEquals(boundKey.toList(), differentlyBoundKey.toList())
	}

	@Test
	fun testResetsTdlibStateWhenMarkerIsPartial() {
		val provider = provider(FakeKeyStrengthener())
		val tdlibDirectory = testFolder.newFolder("tdlib")
		val firstKey = checkNotNull(provider.getDatabaseEncryptionKey(tdlibDirectory))
		val staleState = File(tdlibDirectory, "database/stale").also {
			it.parentFile?.mkdirs()
			it.writeText("stale")
		}
		File(testFolder.root, "key/telegram-tdlib-key.marker").writeText("")

		val recoveredKey = checkNotNull(provider.getDatabaseEncryptionKey(tdlibDirectory))
		val recoveredState = File(tdlibDirectory, "database/recovered").also {
			it.parentFile?.mkdirs()
			it.writeText("recovered")
		}
		val reusedKey = checkNotNull(provider.getDatabaseEncryptionKey(tdlibDirectory))

		assertEquals(false, staleState.exists())
		assertEquals(true, recoveredState.exists())
		assertArrayEquals(firstKey, recoveredKey)
		assertArrayEquals(recoveredKey, reusedKey)
	}

	@Test
	fun testReturnsNoKeyWithoutStrengthener() {
		val provider = provider(null)

		assertEquals(false, provider.isKeyStrengtheningAvailable())
		assertEquals(null, provider.getDatabaseEncryptionKey(testFolder.newFolder("tdlib")))
	}

	@Test
	fun testReturnsNoKeyWhenStrengthenerIsNotInitialised() {
		val provider = provider(FakeKeyStrengthener(initialised = false))

		assertEquals(true, provider.isKeyStrengtheningAvailable())
		assertEquals(null, provider.getDatabaseEncryptionKey(testFolder.newFolder("tdlib")))
	}

	private fun provider(
		strengthener: KeyStrengthener?,
		keyDirectory: File = testFolder.newFolder("key"),
		accountManager: AccountManager = fakeAccountManager(SecretKey(ByteArray(SecretKey.LENGTH))),
	) = ProtectedTelegramTdlibDatabaseKeyProvider(
		config(strengthener, testFolder.newFolder(), keyDirectory),
		accountManager,
	)

	private fun config(strengthener: KeyStrengthener?, dbDir: File, keyDir: File) =
		object : DatabaseConfig {
			override fun getDatabaseDirectory(): File = dbDir
			override fun getDatabaseKeyDirectory(): File = keyDir
			override fun getKeyStrengthener(): KeyStrengthener? = strengthener
		}

	private fun fakeAccountManager(databaseKey: SecretKey?) = object : AccountManager {
		override fun hasDatabaseKey() = databaseKey != null
		override fun getDatabaseKey() = databaseKey
		override fun accountExists() = true
		override fun createAccount(name: String, password: String) = throw UnsupportedOperationException()
		override fun deleteAccount() = throw UnsupportedOperationException()
		override fun signIn(password: String) = throw UnsupportedOperationException()
		override fun changePassword(oldPassword: String, newPassword: String) =
			throw UnsupportedOperationException()
	}

	private class FakeKeyStrengthener(private val initialised: Boolean = true) : KeyStrengthener {
		override fun isInitialised(): Boolean = initialised

		override fun strengthenKey(k: SecretKey): SecretKey = SecretKey(
			k.bytes.mapIndexed { index, byte ->
				(byte.toInt() xor index xor 0x5A).toByte()
			}.toByteArray(),
		)
	}
}
