package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixAuthSession.Snapshot
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.briarproject.briar.matrix.MatrixLoginClient.RestoreResult

/**
 * Matrix password-auth state machine. When [storeConfigurationProvider] supplies a
 * [MatrixStoreConfiguration], a successful login persists to that store's directory so
 * [start] on a fresh instance resumes without re-authenticating; logout invalidates it.
 * `close()` is process/session teardown only and never touches the persisted store.
 */
class MatrixAuthSessionImpl(
	private val discoveryClient: MatrixHomeserverDiscoveryClient,
	private val loginClient: MatrixLoginClient,
	private val storeConfigurationProvider: MatrixStoreConfigurationProvider? = null,
) : MatrixAuthSession {

	private var snapshot = CLOSED_SNAPSHOT

	@Synchronized
	override fun getSnapshot(): Snapshot = snapshot

	@Synchronized
	override fun start() {
		if (snapshot.authState != MatrixAuthState.CLOSED) return
		val storeConfiguration = storeConfigurationProvider?.getStoreConfiguration()
		snapshot = if (storeConfiguration == null) {
			HOMESERVER_SNAPSHOT
		} else {
			when (val result = loginClient.restore(storeConfiguration)) {
				is RestoreResult.Restored -> Snapshot(MatrixAuthState.READY, NONE, result.homeserverUrl)
				is RestoreResult.NotFound -> HOMESERVER_SNAPSHOT
				is RestoreResult.Failed -> Snapshot(MatrixAuthState.RECOVERABLE_ERROR, result.detail)
			}
		}
	}

	@Synchronized
	override fun submitHomeserver(homeserverUrl: String) {
		val canSubmit = snapshot.authState == MatrixAuthState.HOMESERVER_ENTRY ||
			(snapshot.authState == MatrixAuthState.RECOVERABLE_ERROR && snapshot.homeserverUrl == null)
		if (!canSubmit) return
		snapshot = when (val result = discoveryClient.discover(homeserverUrl)) {
			is DiscoveryResult.Resolved -> Snapshot(
				MatrixAuthState.CREDENTIAL_ENTRY,
				NONE,
				result.homeserverUrl,
			)
			is DiscoveryResult.Failed -> Snapshot(MatrixAuthState.RECOVERABLE_ERROR, result.errorDetail)
		}
	}

	@Synchronized
	override fun submitCredentials(username: String, password: String) {
		val homeserverUrl = snapshot.homeserverUrl ?: return
		if (snapshot.authState != MatrixAuthState.CREDENTIAL_ENTRY &&
			snapshot.authState != MatrixAuthState.RECOVERABLE_ERROR
		) {
			return
		}
		val storeConfiguration = storeConfigurationProvider?.getStoreConfiguration()
		val result = loginClient.login(homeserverUrl, username, password, storeConfiguration)
		snapshot = if (result == NONE) {
			Snapshot(MatrixAuthState.READY, NONE, homeserverUrl)
		} else {
			Snapshot(MatrixAuthState.RECOVERABLE_ERROR, result, homeserverUrl)
		}
	}

	@Synchronized
	override fun logout() {
		try {
			loginClient.logout()
		} finally {
			loginClient.close()
			storeConfigurationProvider?.getStoreConfiguration()?.let { it.directory.deleteRecursively() }
			snapshot = HOMESERVER_SNAPSHOT
		}
	}

	@Synchronized
	override fun close() {
		loginClient.close()
		snapshot = CLOSED_SNAPSHOT
	}

	private companion object {
		val HOMESERVER_SNAPSHOT = Snapshot(MatrixAuthState.HOMESERVER_ENTRY, NONE)
		val CLOSED_SNAPSHOT = Snapshot(MatrixAuthState.CLOSED, NONE)
	}
}
