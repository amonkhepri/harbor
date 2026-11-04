package org.briarproject.briar.matrix

import java.util.concurrent.Executor
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.FakeMatrixSdkState
import org.matrix.rustcomponents.sdk.FakeRoomSpec

class MatrixTypingTest {
	private val work = java.util.ArrayDeque<Runnable>()
	private val client = ReflectiveMatrixHomeserverDiscoveryClient(
		discoveryTimeoutMs = 1,
		typingExecutor = Executor { work.add(it) },
	)
	private fun drain() {
		while (work.isNotEmpty()) work.removeFirst().run()
	}
	private fun login() {
		client.login("https://matrix.example.org", "synthetic", "synthetic")
		FakeMatrixSdkState.roomsToReturn = listOf(FakeRoomSpec("!room:example.org"))
	}

	@After fun reset() {
		client.close()
		FakeMatrixSdkState.reset()
	}

	@Test fun delayedNativeStartCannotOvertakeStopAndQueuedRenewalIsFenced() {
		login()
		val session = client.prepareTyping("!room:example.org")!!
		lateinit var completion: Continuation<Unit>
		FakeMatrixSdkState.typingOperation = { if (it) suspendCoroutine { completion = it } }
		var wanted = true
		session.send(true) { wanted }
		drain()
		assertEquals(0, FakeMatrixSdkState.roomCloseCount)
		repeat(10) { session.send(true) { wanted } }
		wanted = false
		session.send(false) { true }
		drain()
		assertEquals(listOf(true), FakeMatrixSdkState.typingCalls)
		completion.resume(Unit)
		drain()
		assertEquals(listOf(true, false), FakeMatrixSdkState.typingCalls)
		assertEquals(2, FakeMatrixSdkState.roomCloseCount)
		session.send(true) { true }
		client.logout()
		drain()
		assertFalse(session.isCurrent())
		login()
		session.send(true) { true }
		drain()
		assertEquals(listOf(true, false), FakeMatrixSdkState.typingCalls)
	}

	@Test fun typingFailureReleasesLaneAndDoesNotBreakTextSend() {
		login()
		val session = client.prepareTyping("!room:example.org")!!
		FakeMatrixSdkState.typingOperation = { throw IllegalStateException("synthetic") }
		session.send(true) { true }
		session.send(false) { true }
		drain()
		assertEquals(listOf(true, false), FakeMatrixSdkState.typingCalls)
		assertEquals(2, FakeMatrixSdkState.roomCloseCount)
		assertTrue(client.sendTextMessage("!room:example.org", "synthetic draft"))
	}

	@Test fun oldSessionCompletionCannotStartItsRenewalOrBlockNewSession() {
		login()
		val old = client.prepareTyping("!room:example.org")!!
		lateinit var completion: Continuation<Unit>
		FakeMatrixSdkState.typingOperation = { suspendCoroutine { completion = it } }
		old.send(true) { true }
		drain()
		old.send(true) { true }
		old.send(false) { true }
		client.logout()
		login()
		FakeMatrixSdkState.typingOperation = {}
		client.prepareTyping("!room:example.org")!!.send(true) { true }
		drain()
		assertEquals(listOf(true, true), FakeMatrixSdkState.typingCalls)
		completion.resume(Unit)
		drain()
		assertEquals(listOf(true, true), FakeMatrixSdkState.typingCalls)
	}
}
