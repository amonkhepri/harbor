package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail

/** Narrow, fakeable boundary around the Matrix SDK password-login client. */
interface MatrixLoginClient {
	/** Returns [RecoverableErrorDetail.NONE] on success and never throws. */
	fun login(homeserverUrl: String, username: String, password: String): RecoverableErrorDetail

	fun logout()
	fun close()
}
