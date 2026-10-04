package org.briarproject.briar.android

import android.app.Application
import org.briarproject.briar.BuildConfig
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class AppModuleMatrixTest {
	@Test
	fun testMatrixReflectiveClientIsNotInitializedWhenDisabled() {
		if (!BuildConfig.MATRIX_CONNECTOR_ENABLED) {
			val module = AppModule(mock(Application::class.java))
			val client = module.provideMatrixReflectiveClient()
			assertNull(
				"Matrix reflective client must be null when disabled to prevent runtime initialization",
				client,
			)
		}
	}

	@Test
	fun testMatrixConnectorIsNotLeakedToRegistryWhenDisabled() {
		if (!BuildConfig.MATRIX_CONNECTOR_ENABLED) {
			val module = org.briarproject.briar.telegram.TelegramModule()
			val registry = module.provideConnectorRegistry(
				org.briarproject.briar.telegram.DisabledTelegramConnector(),
				org.briarproject.briar.matrix.DisabledMatrixConnector(),
			)
			org.junit.Assert.assertTrue(
				"Connector registry should completely exclude the Matrix connector when disabled",
				registry.connectors.none {
					it.source.id ==
						org.briarproject.briar.api.connector.ConnectorSources.MATRIX_ID
				},
			)
		}
	}
}
