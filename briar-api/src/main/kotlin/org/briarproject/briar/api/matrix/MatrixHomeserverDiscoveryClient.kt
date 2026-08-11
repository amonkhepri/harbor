package org.briarproject.briar.api.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail

/**
 * Narrow seam for Matrix homeserver discovery (`.well-known/matrix/client`
 * resolution), kept isolated from login and credential entry so a
 * discovery-only regression can't block `MatrixAuthSession.submitCredentials`.
 * See `docs/plans/mx004_m2_item3_scoping.md` sub-slice 3. This interface has
 * a reflective implementation so `:briar-matrix` stays SDK-free by design
 * while the pinned SDK dependency remains gated in `:briar-android`.
 */
interface MatrixHomeserverDiscoveryClient {
	sealed class DiscoveryResult {
		data class Resolved(val homeserverUrl: String) : DiscoveryResult()
		data class Failed(val errorDetail: RecoverableErrorDetail) : DiscoveryResult()
	}

	/**
	 * Resolves [serverName] (a bare server name, e.g. "example.org", not a
	 * full homeserver URL) to its homeserver base URL. Must not throw; SDK
	 * or network failures are reported through [DiscoveryResult.Failed].
	 */
	fun discover(serverName: String): DiscoveryResult
}
