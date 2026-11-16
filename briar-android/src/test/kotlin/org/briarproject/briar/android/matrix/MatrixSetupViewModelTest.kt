package org.briarproject.briar.android.matrix

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.briarproject.briar.android.connector.ConnectorDraftStore
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.Snapshot
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.briarproject.briar.api.matrix.MatrixDeviceTrustState
import org.briarproject.briar.api.matrix.MatrixVerificationEmoji
import org.briarproject.briar.api.matrix.MatrixVerificationSessionHandle
import org.briarproject.briar.api.matrix.MatrixVerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

class MatrixSetupViewModelTest {
	@get:Rule val rule = InstantTaskExecutorRule()

	@Before
	@After
	fun clearDrafts() = ConnectorDraftStore.clearAll()

	private class QueueExecutor : Executor {
		val actions = ArrayDeque<Runnable>()
		override fun execute(command: Runnable) {
			actions.add(command)
		}
		fun runNext() = actions.removeFirst().run()
	}

	private class Session : MatrixAuthSession {
		var current = Snapshot(MatrixAuthState.CLOSED, RecoverableErrorDetail.NONE)
		var restored = false
		var starts = 0
		var submissions = 0
		var failLogin = false
		var failStart = false
		var logouts = 0
		var failLogout = false
		var failSnapshot = false
		var joins = 0
		var joinAllowed = true
		override fun prepareJoinRoom(identifier: String): (() -> Boolean) = {
			joins++
			joinAllowed
		}
		override fun getSnapshot(): Snapshot {
			if (failSnapshot) error("sensitive snapshot response")
			return current
		}
		override fun start() {
			starts++
			if (failStart) throw IllegalStateException("sensitive SDK response")
			current = Snapshot(
				if (restored) MatrixAuthState.READY else MatrixAuthState.HOMESERVER_ENTRY,
				RecoverableErrorDetail.NONE,
			)
		}
		override fun submitHomeserver(homeserverUrl: String) {
			submissions++
			current = Snapshot(MatrixAuthState.CREDENTIAL_ENTRY, RecoverableErrorDetail.NONE, homeserverUrl)
		}
		override fun submitCredentials(username: String, password: String) {
			submissions++
			if (failLogin) throw IllegalStateException("sensitive SDK response")
			current = current.copy(authState = MatrixAuthState.READY)
		}
		override fun close() = error("UI must not close the shared session")
		override fun logout() {
			logouts++
			restored = false
			current = Snapshot(MatrixAuthState.HOMESERVER_ENTRY, RecoverableErrorDetail.NONE)
			if (failLogout) error("sensitive logout response")
		}
		var deviceTrust = MatrixDeviceTrustState.UNVERIFIED
		var lastVerificationListener: ((MatrixVerificationState) -> Unit)? = null
		var verificationStarts = 0
		var handle: MatrixVerificationSessionHandle? = null
		var returnNullHandle = false
		var deliverComparingSynchronously = false
		var onStartVerification: (() -> Unit)? = null
		var lastCreatedHandle: TestVerificationHandle? = null

		class TestVerificationHandle(
			val listener: (MatrixVerificationState) -> Unit,
			val onConfirm: () -> Unit,
			val onDecline: () -> Unit,
			val onCancel: () -> Unit,
		) : MatrixVerificationSessionHandle {
			var confirmed = false
			var declined = false
			var cancelled = false

			override fun confirm() {
				confirmed = true
				onConfirm()
				listener(MatrixVerificationState.Verified)
			}
			override fun decline() {
				declined = true
				onDecline()
				listener(MatrixVerificationState.Failed)
			}
			override fun cancel() {
				cancelled = true
				onCancel()
				listener(MatrixVerificationState.Cancelled)
			}
		}

		var deviceTrustReads = 0
		override fun getDeviceTrustState(): MatrixDeviceTrustState {
			deviceTrustReads++
			return deviceTrust
		}

		override fun startDeviceVerification(
			listener: (MatrixVerificationState) -> Unit,
			timeoutMs: Long,
		): MatrixVerificationSessionHandle? {
			if (returnNullHandle) return null
			verificationStarts++
			lastVerificationListener = listener
			onStartVerification?.invoke()
			if (deliverComparingSynchronously) {
				listener(MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog"))))
			}
			return TestVerificationHandle(
				listener,
				onConfirm = { deviceTrust = MatrixDeviceTrustState.VERIFIED },
				onDecline = { deviceTrust = MatrixDeviceTrustState.UNVERIFIED },
				onCancel = { deviceTrust = MatrixDeviceTrustState.UNVERIFIED },
			).also {
				handle = it
				lastCreatedHandle = it
			}
		}
	}

	@Test fun joinSuppressesRepeatedTapsAndRetriesFailure() {
		val session = Session().apply {
			restored = true
			joinAllowed = false
		}
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()
		model.joinRoom("#room:example.org")
		model.joinRoom("#room:example.org")
		assertEquals(1, queue.actions.size)
		queue.runNext()
		assertTrue(model.state.value!!.actionFailed)
		session.joinAllowed = true
		model.joinRoom("#room:example.org")
		queue.runNext()
		assertTrue(model.state.value!!.roomJoined)
		assertFalse(model.state.value!!.actionFailed)
		assertEquals(2, session.joins)
	}

	@Test fun disabledSetupNeverStartsOrSubmits() {
		val session = Session()
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, false)
		model.start()
		model.logout(true)
		model.submitHomeserver("https://example.org")
		model.submitCredentials("user", "test password")
		assertTrue(queue.actions.isEmpty())
		assertEquals(0, session.starts)
	}

	@Test fun restorationRunsOnExecutorAndSkipsCredentials() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		model.start()
		assertEquals(0, session.starts)
		assertTrue(model.state.value!!.busy)
		queue.runNext()
		assertEquals(1, session.starts)
		assertEquals(MatrixSetupStep.READY, setupStep(model.state.value!!.snapshot!!))
		model.submitCredentials("user", "test password")
		assertTrue(queue.actions.isEmpty())
		assertEquals(0, session.submissions)
	}

	@Test fun loginActionsAreSerializedAndFailureAllowsCredentialRetry() {
		val session = Session()
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()
		model.submitHomeserver(" https://example.org ")
		model.submitHomeserver("https://duplicate.example")
		assertEquals(0, session.submissions)
		assertEquals(1, queue.actions.size)
		queue.runNext()
		assertEquals("https://example.org", model.state.value!!.snapshot!!.homeserverUrl)
		assertEquals(MatrixSetupStep.CREDENTIALS, setupStep(model.state.value!!.snapshot!!))
		session.failLogin = true
		model.submitCredentials("user", "test password")
		queue.runNext()
		assertTrue(model.state.value!!.actionFailed)
		assertFalse(model.state.value!!.busy)
		assertFalse(model.state.value.toString().contains("sensitive SDK response"))
		session.failLogin = false
		model.submitCredentials("user", "test password")
		queue.runNext()
		assertEquals(MatrixSetupStep.READY, setupStep(model.state.value!!.snapshot!!))
	}

	@Test fun signOutRequiresConfirmationAndReconcilesLocalRemovalOnFailure() {
		for (failLogout in listOf(false, true)) {
			val session = Session().apply {
				restored = true
				this.failLogout = failLogout
			}
			val queue = QueueExecutor()
			val model = MatrixSetupViewModel(session, queue, true)
			model.logout(true)
			assertTrue(queue.actions.isEmpty())
			model.start()
			model.logout(true)
			assertEquals(1, queue.actions.size)
			queue.runNext()
			assertEquals(MatrixSetupStep.READY, setupStep(model.state.value!!.snapshot!!))
			ConnectorDraftStore.setDraft(ConnectorSources.MATRIX_ID, "room", "Matrix draft")
			ConnectorDraftStore.setDraft(ConnectorSources.TELEGRAM_ID, "room", "Telegram draft")
			model.logout(false)
			assertTrue(queue.actions.isEmpty())
			assertEquals(0, session.logouts)
			assertEquals(MatrixSetupStep.READY, setupStep(model.state.value!!.snapshot!!))
			assertEquals("Matrix draft", ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "room"))
			model.logout(true)
			model.logout(true)
			assertTrue(model.state.value!!.busy)
			assertEquals(1, queue.actions.size)
			assertEquals(0, session.logouts)
			queue.runNext()
			assertNull(ConnectorDraftStore.getDraft(ConnectorSources.MATRIX_ID, "room"))
			assertEquals(
				"Telegram draft",
				ConnectorDraftStore.getDraft(ConnectorSources.TELEGRAM_ID, "room"),
			)
			assertEquals(1, session.logouts)
			assertFalse(model.state.value!!.busy)
			assertEquals(MatrixSetupStep.HOMESERVER, setupStep(model.state.value!!.snapshot!!))
			assertEquals(failLogout, model.state.value!!.logoutFailed)
			assertFalse(model.state.value.toString().contains("sensitive"))
			model.logout(true)
			assertTrue(queue.actions.isEmpty())
			val reopened = MatrixSetupViewModel(session, queue, true)
			reopened.start()
			queue.runNext()
			assertEquals(MatrixSetupStep.HOMESERVER, setupStep(reopened.state.value!!.snapshot!!))
		}
	}

	@Test fun unreadableSessionAfterLogoutFailureIsUnavailable() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()
		session.failLogout = true
		session.failSnapshot = true
		model.logout(true)
		queue.runNext()
		assertTrue(model.state.value!!.logoutFailed)
		assertEquals(MatrixSetupStep.UNAVAILABLE, setupStep(model.state.value!!.snapshot!!))
		assertFalse(model.state.value.toString().contains("sensitive"))
	}

	@Test fun startFailureAllowsRetryInsteadOfPermanentlyStranding() {
		val session = Session().apply { failStart = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()
		assertEquals(1, session.starts)
		assertTrue(model.state.value!!.actionFailed)
		assertEquals(MatrixSetupStep.UNAVAILABLE, setupStep(model.state.value!!.snapshot!!))
		assertFalse(model.state.value.toString().contains("sensitive"))
		session.failStart = false
		model.start()
		queue.runNext()
		assertEquals(2, session.starts)
		assertEquals(MatrixSetupStep.HOMESERVER, setupStep(model.state.value!!.snapshot!!))
	}

	@Test fun overlappingStartCallsDoNotPermanentlyStrandAfterFailure() {
		val session = Session().apply { failStart = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		// A second call while the first is still in flight must collapse into the busy guard
		// rather than setting its own "started" expectation that the first call's failure
		// handling then has no way to undo.
		model.start()
		assertEquals(1, queue.actions.size)
		queue.runNext()
		assertEquals(1, session.starts)
		assertFalse(model.state.value!!.started)
		assertTrue(model.state.value!!.actionFailed)
		session.failStart = false
		model.start()
		queue.runNext()
		assertEquals(2, session.starts)
		assertEquals(MatrixSetupStep.HOMESERVER, setupStep(model.state.value!!.snapshot!!))
	}

	@Test fun recoverableErrorsReturnToTheCorrectForm() {
		val discoveryFailure = Snapshot(
			MatrixAuthState.RECOVERABLE_ERROR,
			RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED,
		)
		assertEquals(MatrixSetupStep.HOMESERVER, setupStep(discoveryFailure))
		val loginFailure = discoveryFailure.copy(
			errorDetail = RecoverableErrorDetail.INVALID_CREDENTIALS,
			homeserverUrl = "https://example.org",
		)
		assertEquals(MatrixSetupStep.CREDENTIALS, setupStep(loginFailure))
	}

	@Test fun signedInUserIdIsShownOnlyOnTheReadyStep() {
		val ready = Snapshot(
			MatrixAuthState.READY,
			RecoverableErrorDetail.NONE,
			"https://example.org",
			"@alice:example.org",
		)
		assertEquals("@alice:example.org", signedInUserId(ready))
		assertNull(signedInUserId(ready.copy(userId = null)))
		assertNull(signedInUserId(ready.copy(userId = " ")))
		assertNull(signedInUserId(null))
		// Defensive: even if a stale ID rode along, no other step may show it.
		for (state in listOf(
			MatrixAuthState.CLOSED,
			MatrixAuthState.HOMESERVER_ENTRY,
			MatrixAuthState.CREDENTIAL_ENTRY,
			MatrixAuthState.RECOVERABLE_ERROR,
		)) {
			assertNull(signedInUserId(ready.copy(authState = state)))
		}
	}

	@Test fun verificationFlowTransitionsStateAndUpdatesTrust() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()
		assertEquals(MatrixSetupStep.READY, setupStep(model.state.value!!.snapshot!!))
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)
		assertEquals(MatrixVerificationState.Idle, model.state.value!!.verificationState)

		model.startVerification()
		queue.runNext()
		assertEquals(1, session.verificationStarts)
		assertEquals(MatrixVerificationState.Requesting, model.state.value!!.verificationState)

		session.lastVerificationListener?.invoke(
			MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog"))),
		)
		assertEquals(
			MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog"))),
			model.state.value!!.verificationState,
		)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)

		model.confirmVerification()
		assertEquals(MatrixVerificationState.Verified, model.state.value!!.verificationState)
		assertEquals(MatrixDeviceTrustState.VERIFIED, model.state.value!!.deviceTrustState)
	}

	@Test fun confirmIsIgnoredWhenComparingStateHasEmptyData() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		model.startVerification()
		queue.runNext()
		session.lastVerificationListener?.invoke(
			MatrixVerificationState.Comparing(emptyList()),
		)
		assertEquals(
			MatrixVerificationState.Comparing(emptyList()),
			model.state.value!!.verificationState,
		)

		model.confirmVerification()
		val handle = session.lastCreatedHandle
		assertNotNull(handle)
		assertFalse(handle!!.confirmed)
		assertEquals(
			MatrixVerificationState.Comparing(emptyList()),
			model.state.value!!.verificationState,
		)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)
	}

	@Test fun cancelVerificationDispatchesTrustReadAndCancellationToExecutor() {
		val session = Session().apply { restored = true }
		val bgQueue = QueueExecutor()
		val mainQueue = QueueExecutor()
		val model = MatrixSetupViewModel(session, bgQueue, true, mainQueue)
		model.start()
		bgQueue.runNext()

		model.startVerification()
		bgQueue.runNext()
		mainQueue.runNext()
		assertEquals(MatrixVerificationState.Requesting, model.state.value!!.verificationState)
		val handle = session.lastCreatedHandle
		assertNotNull(handle)
		assertFalse(handle!!.cancelled)

		val readsBeforeCancel = session.deviceTrustReads
		model.cancelVerification()
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)
		assertEquals(readsBeforeCancel, session.deviceTrustReads)
		assertFalse(handle.cancelled)

		bgQueue.runNext()
		assertTrue(handle.cancelled)
		assertEquals(readsBeforeCancel + 1, session.deviceTrustReads)

		mainQueue.runNext()
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)
	}

	@Test fun verificationDeclineLeavesDeviceUntrusted() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		model.startVerification()
		queue.runNext()
		session.lastVerificationListener?.invoke(
			MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog"))),
		)
		model.declineVerification()
		assertEquals(MatrixVerificationState.Failed, model.state.value!!.verificationState)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)
	}

	@Test fun verificationCancelLeavesDeviceUntrusted() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		model.startVerification()
		queue.runNext()
		model.cancelVerification()
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)
	}

	@Test fun verificationStartupFailurePublishesFailedState() {
		val session = Session().apply {
			restored = true
			returnNullHandle = true
		}
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		model.startVerification()
		queue.runNext()
		assertEquals(MatrixVerificationState.Failed, model.state.value!!.verificationState)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)
	}

	@Test fun verificationCallbacksDeliveredBeforeStartupReturnsAreNotOverwritten() {
		val session = Session().apply {
			restored = true
			deliverComparingSynchronously = true
		}
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		model.startVerification()
		queue.runNext()
		assertEquals(
			MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog"))),
			model.state.value!!.verificationState,
		)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)
	}

	@Test fun cancelVerificationBeforeStartupRunsInvalidatesAttempt() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		model.startVerification()
		model.cancelVerification()
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)

		queue.runNext()
		assertEquals(0, session.verificationStarts)
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)
	}

	@Test fun cancelVerificationWhileStartupInFlightCancelsReturnedHandle() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		session.onStartVerification = {
			model.cancelVerification()
		}

		model.startVerification()
		queue.runNext()

		assertEquals(1, session.verificationStarts)
		val handle = session.lastCreatedHandle
		assertNotNull(handle)
		assertTrue(handle!!.cancelled)
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)

		session.lastVerificationListener?.invoke(
			MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog"))),
		)
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)
	}

	@Test fun onClearedWhileStartupInFlightCancelsReturnedHandle() {
		val session = Session().apply { restored = true }
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		session.onStartVerification = {
			model.onCleared()
		}

		model.startVerification()
		queue.runNext()

		assertEquals(1, session.verificationStarts)
		val handle = session.lastCreatedHandle
		assertNotNull(handle)
		assertTrue(handle!!.cancelled)
	}

	@Test fun pendingMainThreadComparingUpdateIsDiscardedAfterCancellation() {
		val session = Session().apply { restored = true }
		val bgQueue = QueueExecutor()
		val mainQueue = QueueExecutor()
		val model = MatrixSetupViewModel(session, bgQueue, true, mainQueue)
		model.start()
		bgQueue.runNext()

		model.startVerification()
		bgQueue.runNext()
		mainQueue.runNext()
		assertEquals(MatrixVerificationState.Requesting, model.state.value!!.verificationState)

		session.lastVerificationListener?.invoke(
			MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog"))),
		)
		assertEquals(1, mainQueue.actions.size)

		model.cancelVerification()
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)

		mainQueue.runNext()
		assertEquals(MatrixVerificationState.Cancelled, model.state.value!!.verificationState)
	}

	@Test fun joinRoomFailureDuringVerificationComparingPreservesComparingStateAndTrust() {
		val session = Session().apply {
			restored = true
			deliverComparingSynchronously = true
			deviceTrust = MatrixDeviceTrustState.UNVERIFIED
		}
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, true)
		model.start()
		queue.runNext()

		model.startVerification()
		queue.runNext()
		val comparing = MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog")))
		assertEquals(comparing, model.state.value!!.verificationState)

		session.joinAllowed = false
		model.joinRoom("#failure:example.org")
		assertTrue(model.state.value!!.busy)
		assertEquals(1, queue.actions.size)
		queue.runNext()

		assertTrue(model.state.value!!.actionFailed)
		assertFalse(model.state.value!!.busy)
		assertEquals(comparing, model.state.value!!.verificationState)
		assertEquals(MatrixDeviceTrustState.UNVERIFIED, model.state.value!!.deviceTrustState)
	}

	@Test fun joinRoomSuccessWhileVerificationTransitionsToComparingDoesNotOverwriteComparingState() {
		val session = Session().apply { restored = true }
		val bgQueue = QueueExecutor()
		val mainQueue = QueueExecutor()
		val model = MatrixSetupViewModel(session, bgQueue, true, mainQueue)
		model.start()
		bgQueue.runNext()

		model.startVerification()
		bgQueue.runNext()
		mainQueue.runNext()
		assertEquals(MatrixVerificationState.Requesting, model.state.value!!.verificationState)

		model.joinRoom("#room:example.org")
		assertEquals(1, bgQueue.actions.size)

		val comparing = MatrixVerificationState.Comparing(listOf(MatrixVerificationEmoji("🐶", "Dog")))
		session.lastVerificationListener?.invoke(comparing)
		assertEquals(1, mainQueue.actions.size)
		mainQueue.runNext()
		assertEquals(comparing, model.state.value!!.verificationState)

		bgQueue.runNext()

		assertTrue(model.state.value!!.roomJoined)
		assertFalse(model.state.value!!.actionFailed)
		assertEquals(comparing, model.state.value!!.verificationState)
	}

	@Test fun joinRoomSuccessWhileVerificationTransitionsToVerifiedPreservesVerifiedStateAndTrust() {
		val session = Session().apply { restored = true }
		val bgQueue = QueueExecutor()
		val mainQueue = QueueExecutor()
		val model = MatrixSetupViewModel(session, bgQueue, true, mainQueue)
		model.start()
		bgQueue.runNext()

		model.startVerification()
		bgQueue.runNext()
		mainQueue.runNext()

		model.joinRoom("#room:example.org")

		session.deviceTrust = MatrixDeviceTrustState.VERIFIED
		session.lastVerificationListener?.invoke(MatrixVerificationState.Verified)
		mainQueue.runNext()
		assertEquals(MatrixVerificationState.Verified, model.state.value!!.verificationState)
		assertEquals(MatrixDeviceTrustState.VERIFIED, model.state.value!!.deviceTrustState)

		bgQueue.runNext()

		assertTrue(model.state.value!!.roomJoined)
		assertEquals(MatrixVerificationState.Verified, model.state.value!!.verificationState)
		assertEquals(MatrixDeviceTrustState.VERIFIED, model.state.value!!.deviceTrustState)
	}
}
