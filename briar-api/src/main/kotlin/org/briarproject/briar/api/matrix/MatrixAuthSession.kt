package org.briarproject.briar.api.matrix

/**
 * Matrix's own login flow (homeserver entry -> credential entry ->
 * ready/error), distinct from Telegram's identifier/code/password steps.
 * No SSO/OIDC, QR login, or device verification: see
 * `docs/plans/mx004_m2_item3_scoping.md` for the out-of-scope boundary.
 */
interface MatrixAuthSession {
	data class Snapshot @JvmOverloads constructor(
		val authState: MatrixAuthState,
		val errorDetail: RecoverableErrorDetail,
		val homeserverUrl: String? = null,
		/**
		 * Matrix user ID of the signed-in account. Non-null only in
		 * [MatrixAuthState.READY]; deliberately omitted from [toString].
		 */
		val userId: String? = null,
	) {
		override fun toString(): String =
			"Snapshot(authState=$authState, errorDetail=$errorDetail, hasHomeserverUrl=${homeserverUrl != null})"
	}

	enum class RecoverableErrorDetail {
		NONE,
		INVALID_HOMESERVER,
		HOMESERVER_DISCOVERY_FAILED,
		INVALID_CREDENTIALS,
		LOGIN_FAILED,
		UNSUPPORTED_AUTH_STEP,
		DEVICE_KEYSTORE_UNAVAILABLE,
		PERSISTED_SESSION_IDENTITY_UNVERIFIED,
		SESSION_EXPIRED,
	}

	fun getSnapshot(): Snapshot
	fun start()
	fun submitHomeserver(homeserverUrl: String)
	fun submitCredentials(username: String, password: String)

	/** Captures the current session before queued join work; failures never expose SDK text. */
	fun prepareJoinRoom(identifier: String): (() -> Boolean)? = null

	fun getDeviceTrustState(): MatrixDeviceTrustState = MatrixDeviceTrustState.UNVERIFIED
	fun startDeviceVerification(
		listener: (MatrixVerificationState) -> Unit,
		timeoutMs: Long = 60_000L,
	): MatrixVerificationSessionHandle? = null

	fun logout()
	fun expireSession() {}
	fun close()
}
