package org.briarproject.briar.android.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorRoomUpdateCoalescerTest {

	@Test
	fun singleUpdateTriggersRefreshImmediately() {
		var refreshCount = 0
		val coalescer = ConnectorRoomUpdateCoalescer { refreshCount++ }

		coalescer.onUpdate()
		assertEquals(1, refreshCount)

		coalescer.onRefreshCompleted()
		assertEquals(1, refreshCount)
	}

	@Test
	fun multipleUpdatesWhileRefreshingAreCoalescedIntoOneFollowUp() {
		var refreshCount = 0
		val coalescer = ConnectorRoomUpdateCoalescer { refreshCount++ }

		coalescer.onUpdate()
		assertEquals(1, refreshCount)

		// Fire 5 updates while first refresh is in-flight
		repeat(5) { coalescer.onUpdate() }
		assertEquals(1, refreshCount)

		// First refresh completes -> follow-up refresh fires once
		coalescer.onRefreshCompleted()
		assertEquals(2, refreshCount)

		// Second refresh completes -> back to idle
		coalescer.onRefreshCompleted()
		assertEquals(2, refreshCount)

		// Subsequent update triggers a fresh refresh
		coalescer.onUpdate()
		assertEquals(3, refreshCount)
		coalescer.onRefreshCompleted()
		assertEquals(3, refreshCount)
	}

	@Test
	fun cancelStopsUpdatesAndPendingRefreshes() {
		var refreshCount = 0
		val coalescer = ConnectorRoomUpdateCoalescer { refreshCount++ }

		coalescer.onUpdate()
		assertEquals(1, refreshCount)

		coalescer.onUpdate() // pending = true
		coalescer.cancel()

		coalescer.onRefreshCompleted()
		assertEquals(1, refreshCount) // pending was cleared by cancel

		coalescer.onUpdate()
		assertEquals(1, refreshCount) // inactive after cancel
	}
}
