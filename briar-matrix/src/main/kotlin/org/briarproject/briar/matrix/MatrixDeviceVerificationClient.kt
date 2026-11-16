package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixDeviceTrustState
import org.briarproject.briar.api.matrix.MatrixVerificationSessionHandle
import org.briarproject.briar.api.matrix.MatrixVerificationState

interface MatrixDeviceVerificationClient {
	fun getDeviceTrustState(): MatrixDeviceTrustState
	fun startVerification(
		listener: (MatrixVerificationState) -> Unit,
		timeoutMs: Long,
	): MatrixVerificationSessionHandle?
}
