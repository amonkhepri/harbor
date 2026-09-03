package org.briarproject.briar.matrix

import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session

/** Builds a direct SDK delegate so storage failures retain the SDK's typed error contract. */
class DirectMatrixClientSessionDelegateFactory : MatrixClientSessionDelegateFactory {
	override fun create(saveSession: (Any) -> Unit, retrieveSession: () -> Any): Any =
		object : ClientSessionDelegate {
			override fun saveSessionInKeychain(session: Session) {
				mapStorageFailure { saveSession(session) }
			}

			override fun retrieveSessionFromKeychain(userId: String): Session =
				mapStorageFailure { retrieveSession() as Session }
		}

	private inline fun <T> mapStorageFailure(action: () -> T): T = try {
		action()
	} catch (e: Exception) {
		throw ClientException.Generic(STORAGE_FAILURE, STORAGE_FAILURE)
	}

	private companion object {
		const val STORAGE_FAILURE = "Matrix session storage failure"
	}
}
