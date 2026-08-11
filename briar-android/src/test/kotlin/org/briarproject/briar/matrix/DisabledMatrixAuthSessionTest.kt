package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.matrix.MatrixAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisabledMatrixAuthSessionTest {
	@Test
	fun testCommandsRemainClosed() {
		val session = DisabledMatrixAuthSession()

		assertClosed(session)
		session.start()
		session.submitHomeserver("")
		session.submitCredentials("", "")
		session.logout()
		session.close()
		assertClosed(session)
	}

	private fun assertClosed(session: DisabledMatrixAuthSession) {
		val snapshot = session.getSnapshot()
		assertEquals(MatrixAuthState.CLOSED, snapshot.authState)
		assertEquals(RecoverableErrorDetail.NONE, snapshot.errorDetail)
		assertNull(snapshot.homeserverUrl)
	}
}
