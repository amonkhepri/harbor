package org.briarproject.briar.android.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorConversationLoadOwnerTest {

	@Test
	fun latestLoadOwnsPublication() {
		val owner = ConnectorConversationLoadOwner()
		val first = owner.begin()
		val second = owner.begin()

		assertFalse(owner.owns(first))
		assertTrue(owner.owns(second))
	}

	@Test
	fun invalidationRevokesPendingLoad() {
		val owner = ConnectorConversationLoadOwner()
		val pending = owner.begin()

		owner.invalidate()

		assertFalse(owner.owns(pending))
	}
}
