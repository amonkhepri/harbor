package org.briarproject.briar.telegram

import dagger.Module
import dagger.Provides
import org.briarproject.bramble.api.FeatureFlags
import org.briarproject.bramble.api.db.DatabaseConfig
import org.briarproject.briar.api.telegram.TelegramAuthSession
import org.briarproject.briar.api.telegram.TelegramConnector
import java.io.File
import javax.inject.Singleton

@Module
class TelegramModule {

	private fun tdlibDirectory(databaseConfig: DatabaseConfig): File {
		val databaseDirectory = databaseConfig.databaseDirectory
		val appPrivateRoot = databaseDirectory.parentFile ?: databaseDirectory
		return File(appPrivateRoot, "tdlib")
	}

	private fun tdlibKeyProvider(databaseConfig: DatabaseConfig): TelegramTdlibDatabaseKeyProvider =
		ProtectedTelegramTdlibDatabaseKeyProvider(databaseConfig)

	@Provides
	@Singleton
	fun provideTelegramConnector(
		featureFlags: FeatureFlags,
		databaseConfig: DatabaseConfig,
	): TelegramConnector = if (featureFlags.shouldEnableTelegramConnector()) {
		StubTelegramConnector(
			ReflectiveTelegramTdlibMessageClient(
				tdlibDirectory(databaseConfig),
				featureFlags.getTelegramApiId(),
				featureFlags.getTelegramApiHash(),
				tdlibKeyProvider(databaseConfig),
			),
		)
	} else {
		NoOpTelegramConnector()
	}

	@Provides
	@Singleton
	fun provideTelegramAuthSession(
		featureFlags: FeatureFlags,
		databaseConfig: DatabaseConfig,
	): TelegramAuthSession = if (featureFlags.shouldEnableTelegramConnector()) {
		TelegramAuthSessionImpl(
			ReflectiveTelegramTdlibLoginClient(
				tdlibDirectory(databaseConfig),
				featureFlags.getTelegramApiId(),
				featureFlags.getTelegramApiHash(),
				tdlibKeyProvider(databaseConfig),
			),
		)
	} else {
		TelegramAuthSessionImpl(NoOpTelegramTdlibLoginClient())
	}
}
