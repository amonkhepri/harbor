package org.briarproject.briar.api.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixAuthSessionShapeTest {

	@Test
	fun `auth state covers homeserver entry through ready or error`() {
		assertEquals(
			setOf(
				MatrixAuthState.HOMESERVER_ENTRY,
				MatrixAuthState.CREDENTIAL_ENTRY,
				MatrixAuthState.READY,
				MatrixAuthState.CLOSED,
				MatrixAuthState.RECOVERABLE_ERROR,
			),
			MatrixAuthState.values().toSet(),
		)
	}

	@Test
	fun `snapshot defaults to no homeserver url when omitted`() {
		val snapshot = MatrixAuthSession.Snapshot(
			authState = MatrixAuthState.HOMESERVER_ENTRY,
			errorDetail = RecoverableErrorDetail.NONE,
		)

		assertEquals(MatrixAuthState.HOMESERVER_ENTRY, snapshot.authState)
		assertEquals(RecoverableErrorDetail.NONE, snapshot.errorDetail)
		assertEquals(null, snapshot.homeserverUrl)
	}

	@Test
	fun `snapshot toString reports presence without leaking homeserver url`() {
		val snapshot = MatrixAuthSession.Snapshot(
			authState = MatrixAuthState.CREDENTIAL_ENTRY,
			errorDetail = RecoverableErrorDetail.NONE,
			homeserverUrl = "https://matrix.example.org",
		)

		val text = snapshot.toString()

		assertTrue(text.contains("hasHomeserverUrl=true"))
		assertFalse(text.contains("matrix.example.org"))
	}
}
