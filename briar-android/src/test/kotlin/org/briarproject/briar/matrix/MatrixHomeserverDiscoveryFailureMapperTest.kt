package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.HOMESERVER_DISCOVERY_FAILED
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.INVALID_HOMESERVER
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixHomeserverDiscoveryFailureMapperTest {

	@Test
	fun `invalid server name maps to invalid homeserver`() {
		assertEquals(
			INVALID_HOMESERVER,
			MatrixHomeserverDiscoveryFailureMapper.mapClientBuildExceptionClassName("InvalidServerName"),
		)
	}

	@Test
	fun `well-known lookup and deserialization failures map to discovery failed`() {
		assertEquals(
			HOMESERVER_DISCOVERY_FAILED,
			MatrixHomeserverDiscoveryFailureMapper.mapClientBuildExceptionClassName("WellKnownLookupFailed"),
		)
		assertEquals(
			HOMESERVER_DISCOVERY_FAILED,
			MatrixHomeserverDiscoveryFailureMapper.mapClientBuildExceptionClassName(
				"WellKnownDeserializationException",
			),
		)
	}

	@Test
	fun `server unreachable maps to discovery failed`() {
		assertEquals(
			HOMESERVER_DISCOVERY_FAILED,
			MatrixHomeserverDiscoveryFailureMapper.mapClientBuildExceptionClassName("ServerUnreachable"),
		)
	}

	@Test
	fun `unrecognised exception class name falls back to discovery failed, not invalid homeserver`() {
		assertEquals(
			HOMESERVER_DISCOVERY_FAILED,
			MatrixHomeserverDiscoveryFailureMapper.mapClientBuildExceptionClassName("Generic"),
		)
		assertEquals(
			HOMESERVER_DISCOVERY_FAILED,
			MatrixHomeserverDiscoveryFailureMapper.mapClientBuildExceptionClassName(
				"SomeFutureSdkExceptionType",
			),
		)
	}
}
