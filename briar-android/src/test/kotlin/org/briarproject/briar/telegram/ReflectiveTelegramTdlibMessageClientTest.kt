package org.briarproject.briar.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ReflectiveTelegramTdlibMessageClientTest {

	@After
	fun tearDown() {
		Client.resetTestState()
	}

	private fun createReadyClient(gate: TelegramTdlibAccessGate): ReflectiveTelegramTdlibMessageClient {
		Client.setAuthorizationStateAfterTdlibParameters(TdApi.AuthorizationStateReady())
		return ReflectiveTelegramTdlibMessageClient(
			tdlibDirectory = File("build/test-tdlib-message-client"),
			apiId = 12345,
			apiHash = "test-api-hash",
			tdlibKeyProvider = StaticTelegramTdlibDatabaseKeyProvider(TDLIB_KEY),
			requestTimeoutMs = 300L,
			accessGate = gate,
		)
	}

	@Test
	fun testUnconfirmedCloseDuringOrdinaryReadDoesNotPoisonGateForLaterShutdown() {
		Client.setCloseAuthorizationUpdateEnabled(false)
		val gate = TelegramTdlibAccessGate()
		val client = createReadyClient(gate)

		assertEquals(true, client.isAuthorized())
		assertEquals(false, gate.hasUnconfirmedClose())

		gate.stop {}
	}

	private companion object {
		private val TDLIB_KEY = ByteArray(32) { (it + 1).toByte() }
	}
}
