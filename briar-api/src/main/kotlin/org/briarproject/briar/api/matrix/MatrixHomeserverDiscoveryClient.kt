package org.briarproject.briar.api.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail

/**
 * Narrow seam for Matrix homeserver discovery (`.well-known/matrix/client`
 * resolution), kept isolated from login and credential entry so a
 * discovery-only regression can't block `MatrixAuthSession.submitCredentials`.
 * See `docs/plans/mx004_m2_item3_scoping.md` sub-slice 3. This interface has
 * no SDK-typed implementation yet: `:briar-matrix` stays SDK-free by design
 * (see `briar-matrix/build.gradle`), so a real implementation needs the same
 * reflective isolation `ReflectiveTelegramTdlibMessageClient` uses for TDLib,
 * bridged over the pinned SDK's suspend `ClientBuilder.build()` call. That
 * bridging is left for a later sub-slice; this seam and its result type are
 * the stable contract a fake and a future reflective client both implement.
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
