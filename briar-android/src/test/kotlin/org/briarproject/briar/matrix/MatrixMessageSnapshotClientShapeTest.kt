package org.briarproject.briar.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MX-007 sub-slice 1 shape proof retained beside the fake-backed reflective implementation tests.
 * [MatrixRoomConnector] still returns its MX-006A "no known latest message" stub until the next
 * separately reviewed wiring slice.
 */
class MatrixMessageSnapshotClientShapeTest {

	@Test
	fun `getRecentMessages returns null to signal load failure and a list to signal known messages`() {
		val failing = object : MatrixMessageSnapshotClient {
			override fun getRecentMessages(roomId: String, limit: Int): List<MatrixMessageSnapshot>? = null
		}
		val loaded = object : MatrixMessageSnapshotClient {
			override fun getRecentMessages(roomId: String, limit: Int) =
				listOf(MatrixMessageSnapshot("\$e1:example.org", "@alice:example.org", "hi", 1L, false))
		}

		assertNull(failing.getRecentMessages("!a:example.org", 20))
		assertEquals(1, loaded.getRecentMessages("!a:example.org", 20)?.size)
	}

	@Test
	fun `MatrixMessageSnapshot exposes plain fields with value equality`() {
		val a = MatrixMessageSnapshot("\$e1:example.org", "@alice:example.org", "hi", 1_000L, false)
		val b = MatrixMessageSnapshot("\$e1:example.org", "@alice:example.org", "hi", 1_000L, false)

		assertEquals(a, b)
		assertEquals("\$e1:example.org", a.eventId)
		assertEquals("@alice:example.org", a.senderId)
		assertEquals("hi", a.bodyText)
		assertEquals(1_000L, a.originServerTimestampSeconds)
		assertEquals(false, a.isOutgoing)
	}
}
