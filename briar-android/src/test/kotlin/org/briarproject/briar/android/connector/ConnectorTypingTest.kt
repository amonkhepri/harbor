package org.briarproject.briar.android.connector

import android.app.Application
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.briarproject.briar.api.connector.ConnectorTypingSender
import org.briarproject.briar.matrix.DisabledMatrixConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConnectorTypingTest {
	private var time = 0L
	private var eligible = true
	private var current = true
	private var scheduled: Runnable? = null
	private var deadline = 0L
	private val calls = mutableListOf<Boolean>()
	private val session = object : ConnectorTypingSender.PreparedTyping {
		override fun isCurrent() = current
		override fun send(typing: Boolean, wanted: () -> Boolean) {
			if (!typing || wanted()) calls.add(typing)
		}
	}
	private val controller = ConnectorTypingController(
		{ session },
		{ eligible },
		{ time },
		{ task, delay ->
			scheduled = task
			deadline = time + delay
		},
		{ scheduled = null },
	)
	private fun advance(to: Long) {
		while (scheduled != null && deadline <= to) {
			time = deadline
			val task = scheduled!!
			scheduled = null
			task.run()
		}
		time = to
	}

	@Test fun editsCoalesceRenewBeforeExpiryAndStopAfterFiveSecondsIdle() {
		controller.edited(true)
		advance(3_000)
		repeat(20) { controller.edited(true) }
		assertEquals(listOf(true), calls)
		advance(3_500)
		assertEquals(listOf(true, true), calls)
		advance(7_000)
		assertEquals(listOf(true, true, true), calls)
		advance(8_000)
		assertEquals(listOf(true, true, true, false), calls)
		assertNull(scheduled)
	}

	@Test fun clearSendFocusAndSessionLossCancelRenewal() {
		controller.edited(true)
		controller.edited(false)
		controller.edited(true)
		controller.stop() // successful send / screen exit
		controller.edited(true)
		eligible = false
		advance(3_500)
		eligible = true
		controller.edited(true)
		current = false
		advance(7_000)
		assertEquals(listOf(true, false, true, false, true, false, true, false), calls)
		assertNull(scheduled)
	}

	@Test fun syntheticImeHardwareAndClearEventsButNotProgrammaticBindsAdvertiseTyping() {
		val view = ConnectorComposeText(RuntimeEnvironment.getApplication(), null)
		view.onUserEdit = { controller.edited(it) }
		view.setText("restored draft")
		assertTrue(calls.isEmpty())
		view.setSelection(view.length())
		val input = view.onCreateInputConnection(EditorInfo())!!
		input.setComposingText("x", 1)
		input.commitText("xy", 1)
		assertEquals(listOf(true), calls)
		view.setText("")
		assertEquals(listOf(true, false), calls)
		view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
		assertEquals(listOf(true, false, true), calls)
		controller.stop()
		assertTrue(view.text.toString().isNotBlank())
		assertFalse(ConnectorTypingSender::class.java.isInstance(DisabledMatrixConnector()))
		assertFalse(
			ConnectorTypingSender::class.java.isAssignableFrom(
				org.briarproject.briar.telegram.ReflectiveTelegramTdlibMessageClient::class.java,
			),
		)
	}
}
