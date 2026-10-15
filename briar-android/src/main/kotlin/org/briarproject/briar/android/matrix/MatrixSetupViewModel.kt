package org.briarproject.briar.android.matrix

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthState
import java.util.concurrent.Executor

internal enum class MatrixSetupStep { HOMESERVER, CREDENTIALS, READY, UNAVAILABLE }

internal fun setupStep(snapshot: MatrixAuthSession.Snapshot): MatrixSetupStep =
	when (snapshot.authState) {
		MatrixAuthState.READY -> MatrixSetupStep.READY
		MatrixAuthState.HOMESERVER_ENTRY -> MatrixSetupStep.HOMESERVER
		MatrixAuthState.CREDENTIAL_ENTRY -> MatrixSetupStep.CREDENTIALS
		MatrixAuthState.RECOVERABLE_ERROR ->
			if (snapshot.homeserverUrl == null) {
				MatrixSetupStep.HOMESERVER
			} else {
				MatrixSetupStep.CREDENTIALS
			}
		MatrixAuthState.CLOSED -> MatrixSetupStep.UNAVAILABLE
	}

internal data class MatrixSetupState(
	val snapshot: MatrixAuthSession.Snapshot? = null,
	val busy: Boolean = false,
	val actionFailed: Boolean = false,
	val logoutFailed: Boolean = false,
	val started: Boolean = false,
)

internal class MatrixSetupViewModel(
	private val session: MatrixAuthSession,
	private val executor: Executor,
	private val enabled: Boolean,
) : ViewModel() {
	private val mutableState = MutableLiveData(MatrixSetupState())
	val state: LiveData<MatrixSetupState> = mutableState

	fun start() {
		if (!enabled || state.value?.started == true) return
		runAction(startingSession = true) { session.start() }
	}

	fun submitHomeserver(homeserver: String) {
		if (currentStep() != MatrixSetupStep.HOMESERVER) return
		runAction { session.submitHomeserver(homeserver.trim()) }
	}

	fun submitCredentials(username: String, password: String) {
		if (currentStep() != MatrixSetupStep.CREDENTIALS) return
		// Credentials live only in this in-flight action, never in observable or saved state.
		runAction { session.submitCredentials(username.trim(), password) }
	}

	fun logout(confirmed: Boolean) {
		if (!confirmed || currentStep() != MatrixSetupStep.READY) return
		runAction(reconcileLogoutFailure = true) { session.logout() }
	}

	private fun currentStep(): MatrixSetupStep? = state.value?.snapshot?.let(::setupStep)

	private fun runAction(
		reconcileLogoutFailure: Boolean = false,
		startingSession: Boolean = false,
		action: () -> Unit,
	) {
		if (!enabled || state.value?.busy == true) return
		val previous = state.value ?: MatrixSetupState()
		mutableState.value = previous.copy(busy = true, actionFailed = false, logoutFailed = false)
		try {
			executor.execute {
				val result = try {
					action()
					MatrixSetupState(session.getSnapshot(), started = true)
				} catch (_: Exception) {
					// SDK exceptions may contain credentials or server responses. Do not log them.
					// A failed start() must be retryable; a failed later action must not re-trigger start().
					val stillStarted = if (startingSession) false else previous.started
					if (reconcileLogoutFailure) {
						val snapshot = try {
							session.getSnapshot()
						} catch (_: Exception) {
							// An unreadable session must not be presented as connected.
							null
						}
						failureState(MatrixSetupState(snapshot, started = stillStarted)).copy(logoutFailed = true)
					} else {
						failureState(previous.copy(started = stillStarted))
					}
				}
				mutableState.postValue(result)
			}
		} catch (_: RuntimeException) {
			val stillStarted = if (startingSession) false else previous.started
			mutableState.value = failureState(previous.copy(started = stillStarted))
				.copy(logoutFailed = reconcileLogoutFailure)
		}
	}

	private fun failureState(previous: MatrixSetupState) = MatrixSetupState(
		previous.snapshot ?: MatrixAuthSession.Snapshot(
			MatrixAuthState.CLOSED,
			RecoverableErrorDetail.NONE,
		),
		actionFailed = true,
		started = previous.started,
	)
}
