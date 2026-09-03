package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.matrix.MatrixConnector

/**
 * Default-off placeholder for the Matrix connector (MX-004). This compiles
 * and runs without the Matrix Rust SDK on the classpath, so it stays valid
 * whether or not the `harbor.matrixConnector.enabled` Gradle property (gated
 * in briar-android/build.gradle) pulls in the pinned
 * `org.matrix.rustcomponents:sdk-android` dependency. The enabled build wires
 * [MatrixRoomConnector] into the registry instead (MX-006A).
 */
class DisabledMatrixConnector : MatrixConnector {
	override val source: ConnectorSource = ConnectorSources.MATRIX

	override fun isEnabled(): Boolean = false

	override fun isAuthorized(): Boolean = false

	override fun getRecentThreads(limit: Int): List<ConnectorThread> = emptyList()

	override fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult =
		ConnectorMessageReadResult.Success(emptyList())
}
