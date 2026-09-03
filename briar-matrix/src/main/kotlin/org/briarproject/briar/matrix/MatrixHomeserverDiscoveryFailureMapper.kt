package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_HOMESERVER

/**
 * Maps the pinned `org.matrix.rustcomponents:sdk-android:26.08.05`
 * `org.matrix.rustcomponents.sdk.ClientBuildException` subtypes (verified
 * against the pinned aar's `ClientBuildException` class hierarchy: `Generic`,
 * `InvalidServerName`, `ServerUnreachable`, `WellKnownDeserializationException`,
 * `WellKnownLookupFailed`, `Sdk`, `SlidingSync`, `SlidingSyncVersion`,
 * `EventCache`, `InvalidRawKey`) to [RecoverableErrorDetail], by simple class
 * name so callers do not need the SDK on their compile classpath (`:briar-matrix`
 * stays SDK-free; see `briar-matrix/build.gradle`). A future reflective
 * discovery client passes `throwable.javaClass.simpleName` here instead of
 * catching SDK-typed exceptions directly.
 */
object MatrixHomeserverDiscoveryFailureMapper {
	private val INVALID_HOMESERVER_EXCEPTION_CLASS_NAMES = setOf(
		"InvalidServerName",
	)

	private val DISCOVERY_FAILED_EXCEPTION_CLASS_NAMES = setOf(
		"ServerUnreachable",
		"WellKnownDeserializationException",
		"WellKnownLookupFailed",
	)

	/**
	 * Returns the [RecoverableErrorDetail] for a `ClientBuildException`
	 * subtype's simple class name. Unrecognised names (including future SDK
	 * exception types this mapping predates) fall back to
	 * [HOMESERVER_DISCOVERY_FAILED] rather than [INVALID_HOMESERVER], since
	 * that is the safer default: it does not tell the user their homeserver
	 * name itself is malformed when the actual cause is unknown.
	 */
	fun mapClientBuildExceptionClassName(simpleClassName: String): RecoverableErrorDetail =
		when (simpleClassName) {
			in INVALID_HOMESERVER_EXCEPTION_CLASS_NAMES -> INVALID_HOMESERVER
			in DISCOVERY_FAILED_EXCEPTION_CLASS_NAMES -> HOMESERVER_DISCOVERY_FAILED
			else -> HOMESERVER_DISCOVERY_FAILED
		}
}
