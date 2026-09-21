package org.briarproject.briar.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.Session
import java.io.File
import java.io.IOException

class MatrixDelegateFencingTest {

	@get:Rule
	val testFolder = TemporaryFolder()

	private fun fakeStoreConfiguration(): MatrixStoreConfiguration = MatrixStoreConfiguration(
		testFolder.newFolder("matrix-sdk-${java.util.UUID.randomUUID()}"),
		"matrix-sdk",
		ByteArray(32) { it.toByte() },
	)

	private fun newSessionClient(): ReflectiveMatrixHomeserverDiscoveryClient =
		ReflectiveMatrixHomeserverDiscoveryClient(
			sessionDelegateFactory = DirectMatrixClientSessionDelegateFactory(),
		)

	@Test
	fun `callbacks crossing logout are rejected`() {
		FakeMatrixSdkState.reset()
		val client = newSessionClient()
		val config = fakeStoreConfiguration()
		client.login("https://matrix.example.org", "alice", "swordfish", config)
		
		val delegate = FakeMatrixSdkState.lastSessionDelegate!!
		val session = Session("1", "2", "3", "4", "5", null, org.matrix.rustcomponents.sdk.SlidingSyncVersion.NATIVE)
		
		// before logout, succeeds
		delegate.saveSessionInKeychain(session)
		
		client.logout()
		
		// after logout, fails
		assertThrows(Exception::class.java) {
			delegate.saveSessionInKeychain(session)
		}
	}
	
	@Test
	fun `callbacks crossing close are rejected`() {
		FakeMatrixSdkState.reset()
		val client = newSessionClient()
		val config = fakeStoreConfiguration()
		client.login("https://matrix.example.org", "alice", "swordfish", config)
		
		val delegate = FakeMatrixSdkState.lastSessionDelegate!!
		val session = Session("1", "2", "3", "4", "5", null, org.matrix.rustcomponents.sdk.SlidingSyncVersion.NATIVE)
		
		client.close()
		
		assertThrows(Exception::class.java) {
			delegate.saveSessionInKeychain(session)
		}
	}

	@Test
	fun `callbacks from failed login are rejected`() {
		FakeMatrixSdkState.reset()
		val client = newSessionClient()
		val config = fakeStoreConfiguration()
		
		FakeMatrixSdkState.failureToThrow = org.matrix.rustcomponents.sdk.ClientBuildException.ServerUnreachable()
		
		client.login("https://matrix.example.org", "alice", "swordfish", config)
		
		val delegate = FakeMatrixSdkState.lastSessionDelegate!!
		val session = Session("1", "2", "3", "4", "5", null, org.matrix.rustcomponents.sdk.SlidingSyncVersion.NATIVE)
		
		assertThrows(Exception::class.java) {
			delegate.saveSessionInKeychain(session)
		}
	}

	@Test
	fun `stale callbacks cannot recreate an active session or access replacement state`() {
		FakeMatrixSdkState.reset()
		val client = newSessionClient()
		val config = fakeStoreConfiguration()
		
		client.login("https://matrix.example.org", "alice", "swordfish", config)
		val oldDelegate = FakeMatrixSdkState.lastSessionDelegate!!
		
		// Replacement session login
		val newConfig = fakeStoreConfiguration()
		client.login("https://matrix.example.org", "bob", "swordfish", newConfig)
		val newDelegate = FakeMatrixSdkState.lastSessionDelegate!!
		
		val session = Session("1", "2", "3", "4", "5", null, org.matrix.rustcomponents.sdk.SlidingSyncVersion.NATIVE)
		
		// Old delegate fails
		assertThrows(Exception::class.java) {
			oldDelegate.saveSessionInKeychain(session)
		}
		
		// New delegate succeeds
		newDelegate.saveSessionInKeychain(session)
	}
}
