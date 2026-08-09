package org.briarproject.briar.matrix

import org.briarproject.briar.api.connector.ConnectorMessageReadResult
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorSources
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector

/**
 * Default-off placeholder for the Matrix connector (MX-004). This compiles
 * and runs without the Matrix Rust SDK on the classpath, so it stays valid
 * whether or not the `harbor.matrixConnector.enabled` Gradle property (gated
 * in briar-android/build.gradle) pulls in the pinned
 * `org.matrix.rustcomponents:sdk-android` dependency. An SDK-backed
 * implementation, Dagger wiring, and connector registry membership are later,
 * separately reviewed slices (M2 steps 3+ in
 * docs/plans/matrix_connector_roadmap.md).
 */
class DisabledMatrixConnector : ReadOnlyConnector {
	override val source: ConnectorSource = ConnectorSources.MATRIX

	override fun isEnabled(): Boolean = false

	override fun isAuthorized(): Boolean = false

	override fun getRecentThreads(limit: Int): List<ConnectorThread> = emptyList()

	override fun getRecentMessageReadResult(threadId: String, limit: Int): ConnectorMessageReadResult =
		ConnectorMessageReadResult.Success(emptyList())
}
