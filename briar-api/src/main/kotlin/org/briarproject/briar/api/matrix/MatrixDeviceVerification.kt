package org.briarproject.briar.api.matrix

enum class MatrixDeviceTrustState {
	UNVERIFIED,
	VERIFIED,
}

sealed class MatrixVerificationState {
	object Idle : MatrixVerificationState()
	object Requesting : MatrixVerificationState()
	object AwaitingPeer : MatrixVerificationState()
	data class Comparing(val emojis: List<MatrixVerificationEmoji>) : MatrixVerificationState()
	object Verified : MatrixVerificationState()
	object Cancelled : MatrixVerificationState()
	object Failed : MatrixVerificationState()
	object TimedOut : MatrixVerificationState()
}

data class MatrixVerificationEmoji(val symbol: String, val description: String)

interface MatrixVerificationSessionHandle {
	fun confirm()
	fun decline()
	fun cancel()
}
