package org.briarproject.briar.headless

import com.fasterxml.jackson.databind.ObjectMapper
import dagger.Module
import dagger.Provides
import org.briarproject.bramble.account.AccountModule
import org.briarproject.bramble.api.FeatureFlags
import org.briarproject.bramble.api.db.DatabaseConfig
import org.briarproject.bramble.api.mailbox.MailboxDirectory
import org.briarproject.bramble.api.plugin.PluginConfig
import org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_CONTROL_PORT
import org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_SOCKS_PORT
import org.briarproject.bramble.api.plugin.TorControlPort
import org.briarproject.bramble.api.plugin.TorDirectory
import org.briarproject.bramble.api.plugin.TorSocksPort
import org.briarproject.bramble.api.plugin.TransportId
import org.briarproject.bramble.api.plugin.duplex.DuplexPluginFactory
import org.briarproject.bramble.api.plugin.simplex.SimplexPluginFactory
import org.briarproject.bramble.battery.DefaultBatteryManagerModule
import org.briarproject.bramble.event.DefaultEventExecutorModule
import org.briarproject.bramble.plugin.tor.MacTorPluginFactory
import org.briarproject.bramble.plugin.tor.UnixTorPluginFactory
import org.briarproject.bramble.plugin.tor.WindowsTorPluginFactory
import org.briarproject.bramble.system.ClockModule
import org.briarproject.bramble.system.DefaultTaskSchedulerModule
import org.briarproject.bramble.system.DefaultThreadFactoryModule
import org.briarproject.bramble.system.DefaultWakefulIoExecutorModule
import org.briarproject.bramble.system.DesktopSecureRandomModule
import org.briarproject.bramble.util.OsUtils.isLinux
import org.briarproject.bramble.util.OsUtils.isMac
import org.briarproject.bramble.util.OsUtils.isWindows
import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.matrix.MatrixConnector
import org.briarproject.briar.headless.blogs.HeadlessBlogModule
import org.briarproject.briar.headless.contact.HeadlessContactModule
import org.briarproject.briar.headless.event.HeadlessEventModule
import org.briarproject.briar.headless.forums.HeadlessForumModule
import org.briarproject.briar.headless.messaging.HeadlessMessagingModule
import java.io.File
import java.util.Collections.emptyList
import javax.inject.Singleton

@Module(
    includes = [
        AccountModule::class,
        ClockModule::class,
        DefaultBatteryManagerModule::class,
        DefaultEventExecutorModule::class,
        DefaultTaskSchedulerModule::class,
        DefaultWakefulIoExecutorModule::class,
        DefaultThreadFactoryModule::class,
        DesktopSecureRandomModule::class,
        HeadlessBlogModule::class,
        HeadlessContactModule::class,
        HeadlessEventModule::class,
        HeadlessForumModule::class,
        HeadlessMessagingModule::class
    ]
)
internal class HeadlessModule(private val appDir: File) {

    @Provides
    @Singleton
    internal fun provideBriarService(briarService: BriarServiceImpl): BriarService = briarService

    @Provides
    @Singleton
    internal fun provideDatabaseConfig(): DatabaseConfig {
        val dbDir = File(appDir, "db")
        val keyDir = File(appDir, "key")
        return HeadlessDatabaseConfig(dbDir, keyDir)
    }

    @Provides
    @MailboxDirectory
    internal fun provideMailboxDirectory(): File {
        return File(appDir, "mailbox")
    }

    @Provides
    @TorDirectory
    internal fun provideTorDirectory(): File {
        return File(appDir, "tor")
    }

    @Provides
    @TorSocksPort
    internal fun provideTorSocksPort(): Int = DEFAULT_SOCKS_PORT

    @Provides
    @TorControlPort
    internal fun provideTorControlPort(): Int = DEFAULT_CONTROL_PORT

    @Provides
    @Singleton
    internal fun providePluginConfig(
        unixTor: UnixTorPluginFactory,
        macTor: MacTorPluginFactory,
        winTor: WindowsTorPluginFactory
    ): PluginConfig {
        val duplex: List<DuplexPluginFactory> = when {
            isLinux() -> listOf(unixTor)
            isMac() -> listOf(macTor)
            isWindows() -> listOf(winTor)
            else -> emptyList()
        }
        return object : PluginConfig {
            override fun getDuplexFactories(): Collection<DuplexPluginFactory> = duplex
            override fun getSimplexFactories(): Collection<SimplexPluginFactory> = emptyList()
            override fun shouldPoll(): Boolean = true
            override fun getTransportPreferences(): Map<TransportId, List<TransportId>> = emptyMap()
        }
    }

    @Provides
    @Singleton
    internal fun provideObjectMapper() = ObjectMapper()

    // Headless has no Matrix login UI or SDK dependency, so it always wires the disabled
    // connector rather than depending on the android-only MATRIX_CONNECTOR_ENABLED build flag.
    @Provides
    @Singleton
    internal fun provideMatrixConnector(): MatrixConnector = object : MatrixConnector {
        override val source: ConnectorSource = ConnectorSources.MATRIX
        override fun isEnabled() = false
        override fun isAuthorized() = false
        override fun getRecentThreads(limit: Int): List<ConnectorThread> = emptyList()
        override fun getRecentMessageReadResult(threadId: String, limit: Int) =
            ConnectorMessageReadResult.Success(emptyList())
    }

    @Provides
    internal fun provideFeatureFlags() = object : FeatureFlags {
        override fun shouldEnableImageAttachments() = false
        override fun shouldEnableProfilePictures() = false
        override fun shouldEnableDisappearingMessages() = false
        override fun shouldEnablePrivateGroupsInCore() = false
        override fun shouldEnableForumsInCore() = true
        override fun shouldEnableBlogsInCore() = true
        override fun shouldEnableTelegramConnector() = false
        override fun getTelegramApiId() = 0
        override fun getTelegramApiHash() = ""
    }
}
