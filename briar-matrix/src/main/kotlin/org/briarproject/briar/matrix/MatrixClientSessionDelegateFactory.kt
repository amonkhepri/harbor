package org.briarproject.briar.matrix

/** Creates the SDK-specific session delegate without exposing SDK types to this module. */
interface MatrixClientSessionDelegateFactory {
	fun create(saveSession: (Any) -> Unit, retrieveSession: () -> Any): Any
}
