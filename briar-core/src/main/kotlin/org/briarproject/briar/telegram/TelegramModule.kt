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

	@Provides
	@Singleton
	fun provideTelegramConnector(featureFlags: FeatureFlags): TelegramConnector =
		if (featureFlags.shouldEnableTelegramConnector()) {
			StubTelegramConnector()
		} else {
			NoOpTelegramConnector()
		}

	@Provides
	@Singleton
	fun provideTelegramAuthSession(
		featureFlags: FeatureFlags,
		databaseConfig: DatabaseConfig,
	): TelegramAuthSession =
		if (featureFlags.shouldEnableTelegramConnector()) {
			TelegramAuthSessionImpl(
				ReflectiveTelegramTdlibLoginClient(
					File(databaseConfig.databaseDirectory, "tdlib"),
				),
			)
		} else {
			TelegramAuthSessionImpl(NoOpTelegramTdlibLoginClient())
		}
}
