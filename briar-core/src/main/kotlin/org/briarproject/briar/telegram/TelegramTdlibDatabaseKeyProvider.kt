package org.briarproject.briar.telegram

import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.db.DatabaseConfig
import org.briarproject.briar.connector.ProtectedConnectorStoreKeyProvider
import java.io.File
import java.security.SecureRandom

interface TelegramTdlibDatabaseKeyProvider {
	fun getDatabaseEncryptionKey(tdlibDirectory: File): ByteArray?

	fun isKeyStrengtheningAvailable(): Boolean = true
}

object NoOpTelegramTdlibDatabaseKeyProvider : TelegramTdlibDatabaseKeyProvider {
	override fun getDatabaseEncryptionKey(tdlibDirectory: File): ByteArray? = null

	override fun isKeyStrengtheningAvailable(): Boolean = false
}

class ProtectedTelegramTdlibDatabaseKeyProvider(
	databaseConfig: DatabaseConfig,
	accountManager: AccountManager,
	random: SecureRandom = SecureRandom(),
) : TelegramTdlibDatabaseKeyProvider {
	private val delegate =
		ProtectedConnectorStoreKeyProvider(databaseConfig, STATE_NAME, accountManager, random)

	override fun isKeyStrengtheningAvailable(): Boolean = delegate.isKeyStrengtheningAvailable()

	override fun getDatabaseEncryptionKey(tdlibDirectory: File): ByteArray? =
		delegate.getStoreEncryptionKey(tdlibDirectory)

	private companion object {
		const val STATE_NAME = "telegram-tdlib-key"
	}
}
