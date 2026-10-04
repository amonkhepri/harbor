package org.briarproject.briar.android

import org.briarproject.briar.BuildConfig
import org.junit.Assert.assertNull
import org.junit.Test

class AppModuleMatrixTest {
    @Test
    fun testMatrixReflectiveClientIsNotInitializedWhenDisabled() {
        if (!BuildConfig.MATRIX_CONNECTOR_ENABLED) {
            val module = AppModule()
            val client = module.provideMatrixReflectiveClient()
            assertNull("Matrix reflective client must be null when disabled to prevent runtime initialization", client)
        }
    }
}
