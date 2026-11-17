package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.SESSION_EXPIRED
import org.briarproject.briar.api.matrix.MatrixAuthSession.Snapshot
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.briarproject.briar.api.matrix.MatrixDeviceTrustState
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient
import org.briarproject.briar.api.matrix.MatrixHomeserverDiscoveryClient.DiscoveryResult
import org.briarproject.briar.api.matrix.MatrixVerificationSessionHandle
import org.briarproject.briar.api.matrix.MatrixVerificationState
import org.briarproject.briar.matrix.MatrixLoginClient.RestoreResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Matrix password-auth state machine. When [storeConfigurationProvider] supplies a
 * [MatrixStoreConfiguration], a successful login persists to that store's directory so
 * [start] on a fresh instance resumes without re-authenticating; logout invalidates it.
 * `close()` is process/session teardown only and never touches the persisted store.
 * Reopening a retired native store requires a cold process; native work may outlive close.
 */
class MatrixAuthSessionImpl(
	private val discoveryClient: MatrixHomeserverDiscoveryClient,
	private val loginClient: MatrixLoginClient,
	private val storeConfigurationProvider: MatrixStoreConfigurationProvider? = null,
) : MatrixAuthSession {

	private var snapshot = CLOSED_SNAPSHOT
	private var isClosed = false

	/**
	 * Set lock-free from the login client's expiry callback (which may hold the client's
	 * own monitor) and folded into [snapshot] on the next read, avoiding lock-order inversion.
	 */
	private val pendingExpiry = AtomicBoolean(false)

	init {
		loginClient.setOnSessionExpiredListener(::expireSession)
	}

	@Synchronized
	override fun getSnapshot(): Snapshot {
		if (snapshot.authState == MatrixAuthState.READY && pendingExpiry.getAndSet(false)) {
			snapshot = Snapshot(MatrixAuthState.RECOVERABLE_ERROR, SESSION_EXPIRED, snapshot.homeserverUrl)
		}
		return snapshot
	}

	@Synchronized
	override fun start() {
		if (isClosed) return
		if (snapshot.authState != MatrixAuthState.CLOSED) return
		pendingExpiry.set(false)
		val storeConfiguration = storeConfigurationProvider?.getStoreConfiguration()
		snapshot = if (storeConfiguration == null) {
			HOMESERVER_SNAPSHOT
		} else {
			when (val result = loginClient.restore(storeConfiguration)) {
				is RestoreResult.Restored -> Snapshot(
					MatrixAuthState.READY,
					NONE,
					result.homeserverUrl,
					loginClient.currentUserId(),
				)
				is RestoreResult.NotFound -> HOMESERVER_SNAPSHOT
				is RestoreResult.Failed -> Snapshot(
					MatrixAuthState.RECOVERABLE_ERROR,
					result.detail,
					result.homeserverUrl ?: snapshot.homeserverUrl,
				)
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
		pendingExpiry.set(false)
		val storeConfiguration = storeConfigurationProvider?.newStoreConfiguration()
		val result = loginClient.login(homeserverUrl, username, password, storeConfiguration)
		snapshot = if (result == NONE) {
			Snapshot(MatrixAuthState.READY, NONE, homeserverUrl, loginClient.currentUserId())
		} else {
			storeConfigurationProvider?.invalidateStore()
			Snapshot(MatrixAuthState.RECOVERABLE_ERROR, result, homeserverUrl)
		}
	}

	@Synchronized
	override fun prepareJoinRoom(identifier: String): (() -> Boolean)? {
		if (getSnapshot().authState != MatrixAuthState.READY) return null
		return (loginClient as? MatrixRoomSnapshotClient)?.prepareJoinRoom(identifier)
	}

	@Synchronized
	override fun getDeviceTrustState(): MatrixDeviceTrustState {
		if (getSnapshot().authState != MatrixAuthState.READY) return MatrixDeviceTrustState.UNVERIFIED
		return (loginClient as? MatrixDeviceVerificationClient)?.getDeviceTrustState()
			?: MatrixDeviceTrustState.UNVERIFIED
	}

	@Synchronized
	override fun startDeviceVerification(
		listener: (MatrixVerificationState) -> Unit,
		timeoutMs: Long,
	): MatrixVerificationSessionHandle? {
		if (getSnapshot().authState != MatrixAuthState.READY) return null
		return (loginClient as? MatrixDeviceVerificationClient)?.startVerification(listener, timeoutMs)
	}

	@Synchronized
	override fun logout() {
		if (isClosed) return
		try {
			loginClient.logout()
		} finally {
			try {
				storeConfigurationProvider?.invalidateStore()
			} finally {
				loginClient.close()
				snapshot = HOMESERVER_SNAPSHOT
			}
		}
	}

	/** Marks a READY session expired; homeserver URL is kept so reconnect skips discovery. */
	override fun expireSession() {
		pendingExpiry.set(true)
	}

	@Synchronized
	override fun close() {
		if (isClosed) return
		isClosed = true
		loginClient.close()
		snapshot = CLOSED_SNAPSHOT
	}

	private companion object {
		val HOMESERVER_SNAPSHOT = Snapshot(MatrixAuthState.HOMESERVER_ENTRY, NONE)
		val CLOSED_SNAPSHOT = Snapshot(MatrixAuthState.CLOSED, NONE)
	}
}
