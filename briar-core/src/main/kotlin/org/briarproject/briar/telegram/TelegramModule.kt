package org.briarproject.briar.telegram

import dagger.Module
import dagger.Provides
import org.briarproject.bramble.api.FeatureFlags
import org.briarproject.bramble.api.db.DatabaseConfig
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.briar.api.connector.ConnectorRegistry
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

	@Provides
	@Singleton
	fun provideTelegramTdlibAccessGate(): TelegramTdlibAccessGate = TelegramTdlibAccessGate()

	@Provides
	@Singleton
	fun provideTelegramTdlibDatabaseKeyProvider(
		databaseConfig: DatabaseConfig,
	): TelegramTdlibDatabaseKeyProvider = ProtectedTelegramTdlibDatabaseKeyProvider(databaseConfig)

	@Provides
	@Singleton
	fun provideTelegramConnector(
		featureFlags: FeatureFlags,
		databaseConfig: DatabaseConfig,
		tdlibKeyProvider: TelegramTdlibDatabaseKeyProvider,
		accessGate: TelegramTdlibAccessGate,
	): TelegramConnector = if (featureFlags.shouldEnableTelegramConnector()) {
		ReflectiveTelegramTdlibMessageClient(
			tdlibDirectory(databaseConfig),
			featureFlags.getTelegramApiId(),
			featureFlags.getTelegramApiHash(),
			tdlibKeyProvider,
			accessGate = accessGate,
		)
	} else {
		DisabledTelegramConnector()
	}

	@Provides
	@Singleton
	fun provideConnectorRegistry(telegramConnector: TelegramConnector): ConnectorRegistry =
		object : ConnectorRegistry {
			override val connectors = listOf(telegramConnector)
		}

	@Provides
	@Singleton
	fun provideTelegramAuthSession(
		featureFlags: FeatureFlags,
		databaseConfig: DatabaseConfig,
		tdlibKeyProvider: TelegramTdlibDatabaseKeyProvider,
		accessGate: TelegramTdlibAccessGate,
		lifecycleManager: LifecycleManager,
	): TelegramAuthSession {
		val session = if (featureFlags.shouldEnableTelegramConnector()) {
			TelegramAuthSessionImpl(
				ReflectiveTelegramTdlibLoginClient(
					tdlibDirectory(databaseConfig),
					featureFlags.getTelegramApiId(),
					featureFlags.getTelegramApiHash(),
					tdlibKeyProvider,
				),
				accessGate,
			)
		} else {
			TelegramAuthSessionImpl(DisabledTelegramTdlibLoginClient(), accessGate)
		}
		lifecycleManager.registerService(session)
		return session
	}
}
