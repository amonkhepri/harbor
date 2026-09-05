package org.briarproject.briar.android.matrix

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.Snapshot
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

class MatrixSetupViewModelTest {
	@get:Rule val rule = InstantTaskExecutorRule()

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
		override fun getSnapshot() = current
		override fun start() {
			starts++
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
		override fun logout() = error("UI must not log out the shared session")
	}

	@Test fun disabledSetupNeverStartsOrSubmits() {
		val session = Session()
		val queue = QueueExecutor()
		val model = MatrixSetupViewModel(session, queue, false)
		model.start()
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
}
