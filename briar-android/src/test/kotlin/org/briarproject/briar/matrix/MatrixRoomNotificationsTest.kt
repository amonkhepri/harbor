package org.briarproject.briar.matrix

import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail.NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec
import org.matrix.rustcomponents.sdk.NotificationSettings

class MatrixRoomNotificationsTest {
	private val client = ReflectiveMatrixHomeserverDiscoveryClient()
	private val room = "!room:example.org"

	@After fun reset() {
		client.close()
		FakeMatrixSdkState.reset()
		NotificationSettings.modes.clear()
		NotificationSettings.failUpdate = false
		NotificationSettings.ignoreUpdate = false
	}

	private fun login() {
		assertEquals(NONE, client.login("https://matrix.example.org", "alice", "password"))
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec(room, isDirect = false))
	}

	@Test fun `server mute survives session recreation and failed write preserves confirmed mode`() {
		login()
		val preference = client.prepareRoomNotifications(room)!!
		assertEquals(false, preference.readMuted())
		assertEquals(true, preference.setMuted(true))
		NotificationSettings.failUpdate = true
		assertNull(preference.setMuted(false))
		assertEquals(true, preference.readMuted())
		client.close()
		login()
		assertFalse(preference.isCurrent())
		assertNull(preference.setMuted(false))
		val restored = client.prepareRoomNotifications(room)!!
		assertEquals(true, restored.readMuted())
		NotificationSettings.failUpdate = false
		assertEquals(false, restored.setMuted(false))
	}

	@Test fun `acknowledged write requires matching server readback`() {
		login()
		val preference = client.prepareRoomNotifications(room)!!
		NotificationSettings.ignoreUpdate = true
		assertNull(preference.setMuted(true))
		assertEquals(false, preference.readMuted())
		NotificationSettings.ignoreUpdate = false
		assertEquals(true, preference.setMuted(true))
		NotificationSettings.ignoreUpdate = true
		assertNull(preference.setMuted(false))
		assertEquals(true, preference.readMuted())
	}

	@Test fun `missing room and missing session do not infer unmuted`() {
		assertNull(client.prepareRoomNotifications(room))
		login()
		assertNull(client.prepareRoomNotifications("!missing:example.org")!!.readMuted())
	}
}
