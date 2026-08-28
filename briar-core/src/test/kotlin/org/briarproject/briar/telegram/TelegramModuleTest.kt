package org.briarproject.briar.telegram

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File

class TelegramModuleTest {

	private lateinit var root: File
	private val module = TelegramModule()

	@Before
	fun setUp() {
		root = File.createTempFile("telegram-module-test", "").apply {
			delete()
			mkdirs()
		}
	}

	@After
	fun tearDown() {
		root.deleteRecursively()
	}

	@Test
	fun `migrates an existing sibling tdlib store into the nested location`() {
		val legacy = File(root, "tdlib")
		val nested = File(File(root, "app_db"), "tdlib")
		legacy.mkdirs()
		File(legacy, "session.bin").writeText("authorized-session")

		val result = module.migrateLegacyTdlibDirectory(legacy, nested)

		assertEquals(nested, result)
		assertFalse("legacy directory should be gone after migration", legacy.exists())
		assertEquals("authorized-session", File(nested, "session.bin").readText())
	}

	@Test
	fun `completes migration when a data-bearing legacy meets an empty nested stub`() {
		val legacy = File(root, "tdlib")
		val nested = File(File(root, "app_db"), "tdlib")
		legacy.mkdirs()
		File(legacy, "session.bin").writeText("authorized-session")
		nested.mkdirs() // pre-existing empty stub, not a real migrated store

		val result = module.migrateLegacyTdlibDirectory(legacy, nested)

		assertEquals(nested, result)
		assertFalse(legacy.exists())
		assertEquals("authorized-session", File(nested, "session.bin").readText())
	}

	@Test
	fun `preserves a data-bearing legacy store when a non-empty nested store already exists`() {
		val legacy = File(root, "tdlib")
		val nested = File(File(root, "app_db"), "tdlib")
		legacy.mkdirs()
		File(legacy, "session.bin").writeText("authorized-session")
		nested.mkdirs()
		File(nested, "other.bin").writeText("unverified") // not provably a stub

		val result = module.migrateLegacyTdlibDirectory(legacy, nested)

		assertEquals(legacy, result)
		assertEquals("authorized-session", File(legacy, "session.bin").readText())
	}

	@Test
	fun `keeps using the legacy store instead of losing it when migration fails`() {
		val legacy = File(root, "tdlib")
		val nested = File(File(root, "app_db"), "tdlib")
		legacy.mkdirs()
		File(legacy, "session.bin").writeText("authorized-session")
		// Occupy the nested parent with a file so renameTo cannot succeed,
		// simulating a failed migration (e.g. cross-filesystem rename).
		nested.parentFile!!.writeText("not a directory")

		val result = module.migrateLegacyTdlibDirectory(legacy, nested)

		assertEquals(legacy, result)
		assertEquals("authorized-session", File(legacy, "session.bin").readText())
	}
}
