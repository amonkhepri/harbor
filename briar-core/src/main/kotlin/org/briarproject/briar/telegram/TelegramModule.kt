package org.briarproject.briar.telegram

import dagger.Module
import dagger.Provides
import org.briarproject.bramble.api.FeatureFlags
import org.briarproject.bramble.api.account.AccountManager
import org.briarproject.bramble.api.db.DatabaseConfig
import org.briarproject.bramble.api.lifecycle.LifecycleManager
import org.briarproject.bramble.util.IoUtils
import org.briarproject.briar.api.connector.ConnectorRegistry
import org.briarproject.briar.api.matrix.MatrixConnector
import org.briarproject.briar.api.telegram.TelegramAuthSession
import org.briarproject.briar.api.telegram.TelegramConnector
import java.io.File
import java.util.logging.Logger
import javax.inject.Singleton

@Module
class TelegramModule {

	private companion object {
		private val LOG: Logger = Logger.getLogger(TelegramModule::class.java.name)
	}

	// Nested inside databaseDirectory so AccountManagerImpl.deleteAccount()'s
	// unconditional deletion of it always removes tdlib too. Existing installs
	// on the old sibling layout are migrated in place (P353-001) so a live
	// session is never silently dropped in favour of an empty new store.
	private fun tdlibDirectory(databaseConfig: DatabaseConfig): File {
		val databaseDirectory = databaseConfig.databaseDirectory
		val legacy = File(databaseDirectory.parentFile ?: databaseDirectory, "tdlib")
		val nested = File(databaseDirectory, "tdlib")
		return migrateLegacyTdlibDirectory(legacy, nested)
	}

	// Returns the directory TDLib should use, migrating a pre-existing sibling
	// store into `nested`. Falls back to `legacy` unchanged if migration isn't
	// possible, so a live session survives instead of being abandoned.
	internal fun migrateLegacyTdlibDirectory(legacy: File, nested: File): File {
		if (legacy == nested || !legacy.exists()) return nested
		if (nested.exists()) {
			// nested's mere existence doesn't prove it's a valid replacement
			// (P354-001); only an empty stub is a safe stale leftover.
			if (nested.listFiles()?.isEmpty() != true) return if (legacy.listFiles()?.isEmpty() == true) nested else legacy
			IoUtils.deleteFileOrDir(nested)
		}
		val parent = nested.parentFile
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			LOG.warning("Could not create parent directory for tdlib migration")
			return legacy
		}
		if (!legacy.renameTo(nested)) {
			LOG.warning("Could not migrate legacy tdlib directory")
			return legacy
		}
		return nested
	}

	@Provides
	@Singleton
	fun provideTelegramTdlibAccessGate(): TelegramTdlibAccessGate = TelegramTdlibAccessGate()

	@Provides
	@Singleton
	fun provideTelegramTdlibDatabaseKeyProvider(
		databaseConfig: DatabaseConfig,
		accountManager: AccountManager,
	): TelegramTdlibDatabaseKeyProvider =
		ProtectedTelegramTdlibDatabaseKeyProvider(databaseConfig, accountManager)

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
	fun provideConnectorRegistry(
		telegramConnector: TelegramConnector,
		matrixConnector: MatrixConnector,
	): ConnectorRegistry = object : ConnectorRegistry {
		override val connectors = listOf(telegramConnector, matrixConnector)
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
