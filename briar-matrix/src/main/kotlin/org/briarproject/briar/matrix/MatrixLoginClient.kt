package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail

/** Narrow, fakeable boundary around the Matrix SDK password-login and session-restore client. */
interface MatrixLoginClient {
	/**
	 * Returns [RecoverableErrorDetail.NONE] on success and never throws. When
	 * [storeConfiguration] is non-null, the SDK client persists its session to
	 * that store's directory/passphrase instead of keeping it in memory, so a
	 * later [restore] against the same configuration can resume it.
	 */
	fun login(
		homeserverUrl: String,
		username: String,
		password: String,
		storeConfiguration: MatrixStoreConfiguration? = null,
	): RecoverableErrorDetail

	/**
	 * Attempts to resume a session previously persisted at [storeConfiguration]'s
	 * directory. Never throws.
	 */
	fun restore(storeConfiguration: MatrixStoreConfiguration): RestoreResult

	fun logout()
	fun close()

	sealed class RestoreResult {
		data class Restored(val homeserverUrl: String) : RestoreResult()
		object NotFound : RestoreResult()
		data class Failed(val detail: RecoverableErrorDetail) : RestoreResult()
	}
}
