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
	) {
		override fun toString(): String =
			"Snapshot(authState=$authState, errorDetail=$errorDetail, hasHomeserverUrl=${homeserverUrl != null})"
	}

	enum class RecoverableErrorDetail {
		NONE,
		INVALID_HOMESERVER,
		HOMESERVER_DISCOVERY_FAILED,
		INVALID_CREDENTIALS,
		UNSUPPORTED_AUTH_STEP,
		DEVICE_KEYSTORE_UNAVAILABLE,
		PERSISTED_SESSION_IDENTITY_UNVERIFIED,
	}

	fun getSnapshot(): Snapshot
	fun start()
	fun submitHomeserver(homeserverUrl: String)
	fun submitCredentials(username: String, password: String)
	fun logout()
	fun close()
}
