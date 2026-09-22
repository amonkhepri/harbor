package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.LOGIN_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.Session
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class MatrixDelegateFencingTest {
	@get:Rule val folder = TemporaryFolder()

	@After fun reset() = FakeMatrixSdkState.reset()
	private fun store() = MatrixStoreConfiguration(folder.newFolder(), "matrix-sdk", ByteArray(32))
	private fun client() = ReflectiveMatrixHomeserverDiscoveryClient(
		sessionDelegateFactory = DirectMatrixClientSessionDelegateFactory(),
	)
	private fun login(
		client: ReflectiveMatrixHomeserverDiscoveryClient,
		store: MatrixStoreConfiguration,
	) {
		assertEquals(NONE, client.login("https://matrix.example.org", "alice", "password", store))
	}
	private fun rejected(delegate: ClientSessionDelegate, session: Session) {
		assertThrows(ClientException::class.java) { delegate.saveSessionInKeychain(session) }
		assertThrows(ClientException::class.java) { delegate.retrieveSessionFromKeychain(session.userId) }
	}

	@Test fun `delayed writes cannot cross logout close login restore or failed login`() {
		for (transition in listOf("logout", "close", "login", "restore", "failed", "missing")) {
			FakeMatrixSdkState.reset()
			val client = client()
			val old = store()
			login(client, old)
			val delegate = FakeMatrixSdkState.lastSessionDelegate!!
			val session = FakeMatrixSdkState.lastBuiltClient!!.session()
			val replacement = store()
			// A persisted cold-process fixture, isolated from the live native store.
			if (transition == "restore") {
				File(old.directory, "session")
					.copyTo(File(replacement.directory, "session"))
			}
			val entered = CountDownLatch(1)
			val release = CountDownLatch(1)
			FakeMatrixSdkState.onSessionRead = {
				entered.countDown()
				check(release.await(5, SECONDS))
			}
			val pool = Executors.newFixedThreadPool(2)
			try {
				val write = pool.submit {
					assertThrows(ClientException::class.java) { delegate.saveSessionInKeychain(session) }
				}
				assertTrue(entered.await(5, SECONDS))
				FakeMatrixSdkState.onSessionRead = null
				pool.submit {
					when (transition) {
						"logout" -> client.logout()
						"close" -> client.close()
						"login" -> login(client, replacement)
						"restore" -> assertTrue(
							client.restore(replacement) is MatrixLoginClient.RestoreResult.Restored,
						)
						"missing" -> assertEquals(
							MatrixLoginClient.RestoreResult.NotFound,
							client.restore(replacement),
						)
						else -> {
							FakeMatrixSdkState.onLogin = { throw ClientException.Generic("failed", "failed") }
							assertEquals(
								LOGIN_FAILED,
								client.login("https://matrix.example.org", "bob", "bad", replacement),
							)
							assertFalse(File(replacement.directory, "session").exists())
						}
					}
				}.get(3, SECONDS) // Must finish while the old SDK getter is still blocked.
				val replacementBytes = File(replacement.directory, "session").takeIf {
					it.exists()
				}?.readBytes()
				// Test-only removal: production must quarantine, never delete native stores.
				old.directory.deleteRecursively()
				release.countDown()
				write.get(3, SECONDS)
				rejected(delegate, session)
				assertFalse(old.directory.exists())
				if (replacementBytes != null) {
					assertArrayEquals(replacementBytes, File(replacement.directory, "session").readBytes())
					assertEquals(
						session.userId,
						FakeMatrixSdkState.lastSessionDelegate!!
							.retrieveSessionFromKeychain(session.userId).userId,
					)
				}
			} finally {
				release.countDown()
				pool.shutdownNow()
				FakeMatrixSdkState.reset()
				client.close()
			}
		}
	}

	@Test fun `disposal can wait for callback threads without lifecycle deadlock`() {
		for (transition in listOf("logout", "close", "replacement", "restore", "failed")) {
			FakeMatrixSdkState.reset()
			val client = client()
			val store = store()
			login(client, store)
			val delegate = FakeMatrixSdkState.lastSessionDelegate!!
			val session = FakeMatrixSdkState.lastBuiltClient!!.session()
			val pool = Executors.newSingleThreadExecutor()
			var callbacks = 0
			try {
				FakeMatrixSdkState.onDispose = {
					pool.submit { rejected(delegate, session) }.get(3, SECONDS)
					callbacks++
				}
				when (transition) {
					"logout" -> client.logout()
					"close" -> client.close()
					"replacement" -> login(client, store())
					"restore" -> {
						val cold = store()
						File(store.directory, "session").copyTo(File(cold.directory, "session"))
						assertTrue(client.restore(cold) is MatrixLoginClient.RestoreResult.Restored)
					}
					else -> {
						FakeMatrixSdkState.failureToThrow = ClientBuildException.Generic()
						assertEquals(LOGIN_FAILED, client.login("https://matrix.example.org", "bob", "bad", store()))
					}
				}
				assertEquals(if (transition == "logout") 2 else 1, callbacks)
				assertEquals(transition != "logout", File(store.directory, "session").exists())
				assertTrue(File(store.directory, "data").isDirectory)
			} finally {
				FakeMatrixSdkState.onDispose = null
				pool.shutdownNow()
			}
		}
	}

	@Test fun `failed login fences its own delayed writer and removes early sidecar`() {
		val client = client()
		val store = store()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val pool = Executors.newSingleThreadExecutor()
		var write: java.util.concurrent.Future<*>? = null
		try {
			FakeMatrixSdkState.onLogin = {
				val delegate = FakeMatrixSdkState.lastSessionDelegate!!
				val session = FakeMatrixSdkState.lastBuiltClient!!.session()
				FakeMatrixSdkState.onSessionRead = {
					entered.countDown()
					check(release.await(5, SECONDS))
				}
				write =
					pool.submit {
						assertThrows(ClientException::class.java) { delegate.saveSessionInKeychain(session) }
					}
				check(entered.await(3, SECONDS))
				throw ClientException.Generic("failed", "failed")
			}
			assertEquals(LOGIN_FAILED, client.login("https://matrix.example.org", "alice", "bad", store))
			assertFalse(File(store.directory, "session").exists())
			release.countDown()
			write!!.get(3, SECONDS)
			assertFalse(File(store.directory, "session").exists())
		} finally {
			release.countDown()
			pool.shutdownNow()
		}
	}

	@Test fun `reads paused in SDK reconstruction are rejected after invalidation`() {
		for (transition in listOf("logout", "close", "replacement", "failed")) {
			FakeMatrixSdkState.reset()
			val client = client()
			login(client, store())
			val delegate = FakeMatrixSdkState.lastSessionDelegate!!
			val entered = CountDownLatch(1)
			val release = CountDownLatch(1)
			val pool = Executors.newFixedThreadPool(2)
			try {
				FakeMatrixSdkState.onSessionConstruct =
					{
						entered.countDown()
						check(release.await(5, SECONDS))
					}
				val read = pool.submit {
					assertThrows(ClientException::class.java) { delegate.retrieveSessionFromKeychain("alice") }
				}
				assertTrue(entered.await(3, SECONDS))
				FakeMatrixSdkState.onSessionConstruct = null
				pool.submit {
					when (transition) {
						"logout" -> client.logout()
						"close" -> client.close()
						"replacement" -> login(client, store())
						else -> {
							FakeMatrixSdkState.failureToThrow = ClientBuildException.Generic()
							assertEquals(LOGIN_FAILED, client.login("https://matrix.example.org", "bob", "bad", store()))
						}
					}
				}.get(3, SECONDS)
				release.countDown()
				read.get(3, SECONDS)
			} finally {
				release.countDown()
				pool.shutdownNow()
				client.close()
			}
		}
	}

	@Test fun `late native store activity remains isolated from replacement`() {
		val client = client()
		val retired = store()
		login(client, retired)
		val nativeFile = File(retired.directory, "data/native.db").also { it.writeText("old") }
		client.logout()
		val replacement = store()
		login(client, replacement)
		val session = File(replacement.directory, "session").readBytes()
		// Stand-in for native work still owning its old path after wrapper disposal.
		nativeFile.writeText("late native write")
		assertTrue(nativeFile.isFile)
		assertFalse(File(replacement.directory, "data/native.db").exists())
		assertArrayEquals(session, File(replacement.directory, "session").readBytes())
		client.close()
	}

	@Test fun `retired paths cannot be opened by replacement clients even after close`() {
		val client = client()
		val store = store()
		login(client, store)
		client.close()
		val bytes = File(store.directory, "session").readBytes()
		assertEquals(MatrixLoginClient.RestoreResult.NotFound, client().restore(store))
		assertEquals(LOGIN_FAILED, client().login("https://matrix.example.org", "bob", "password", store))
		assertArrayEquals(bytes, File(store.directory, "session").readBytes())
		assertEquals(1, FakeMatrixSdkState.loginCallCount)
	}
}
