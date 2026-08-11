package org.briarproject.briar.api.matrix

enum class MatrixAuthState {
	HOMESERVER_ENTRY,
	CREDENTIAL_ENTRY,
	READY,
	CLOSED,
	RECOVERABLE_ERROR,
}
