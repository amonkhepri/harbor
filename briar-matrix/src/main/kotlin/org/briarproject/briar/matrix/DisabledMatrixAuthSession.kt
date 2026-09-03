package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthSession.Snapshot
import org.briarproject.briar.api.matrix.MatrixAuthState

class DisabledMatrixAuthSession : MatrixAuthSession {
	override fun getSnapshot(): Snapshot = CLOSED_SNAPSHOT

	override fun start() = Unit

	override fun submitHomeserver(homeserverUrl: String) = Unit

	override fun submitCredentials(username: String, password: String) = Unit

	override fun logout() = Unit

	override fun close() = Unit

	private companion object {
		val CLOSED_SNAPSHOT = Snapshot(MatrixAuthState.CLOSED, RecoverableErrorDetail.NONE)
	}
}
