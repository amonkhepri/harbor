package org.briarproject.briar.telegram

import org.briarproject.bramble.api.crypto.KeyStrengthener
import org.briarproject.bramble.api.crypto.SecretKey
import org.briarproject.bramble.api.db.DatabaseConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

		assertEquals(false, staleState.exists())
		assertArrayEquals(firstKey, recoveredKey)
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

	private fun provider(strengthener: KeyStrengthener?) = ProtectedTelegramTdlibDatabaseKeyProvider(
		config(strengthener, testFolder.newFolder("db"), testFolder.newFolder("key")),
	)

	private fun config(strengthener: KeyStrengthener?, dbDir: File, keyDir: File) =
		object : DatabaseConfig {
			override fun getDatabaseDirectory(): File = dbDir
			override fun getDatabaseKeyDirectory(): File = keyDir
			override fun getKeyStrengthener(): KeyStrengthener? = strengthener
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
