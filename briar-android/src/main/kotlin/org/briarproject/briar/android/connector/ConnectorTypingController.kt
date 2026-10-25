package org.briarproject.briar.android.connector

import org.briarproject.briar.api.connector.ConnectorTypingSender.PreparedTyping
import java.util.concurrent.atomic.AtomicBoolean

/** UI-thread scheduler; queued native work only captures an atomic lease and monotonic time. */
internal class ConnectorTypingController(
	private val prepare: () -> PreparedTyping?,
	private val eligible: () -> Boolean,
	private val now: () -> Long,
	private val schedule: (Runnable, Long) -> Unit,
	private val cancel: (Runnable) -> Unit,
) {
	private var session: PreparedTyping? = null
	private var lease = AtomicBoolean(false)

	@Volatile private var lastEdit = 0L
	private var lastStart = 0L
	private val tick = Runnable { update() }

	fun edited(nonBlank: Boolean) {
		if (!nonBlank || !eligible()) {
			stop()
			return
		}
		lastEdit = now()
		if (!lease.get()) {
			session = prepare() ?: return
			lease = AtomicBoolean(true)
			lastStart = now() - RENEW_MS
		}
		update()
	}

	private fun update() {
		cancel(tick)
		if (!lease.get()) return
		if (!eligible() || session?.isCurrent() != true || now() - lastEdit >= IDLE_MS) {
			stop()
			return
		}
		if (now() - lastStart >= RENEW_MS) {
			lastStart = now()
			val active = lease
			session?.send(true) { active.get() && now() - lastEdit < IDLE_MS }
		}
		schedule(tick, minOf(lastStart + RENEW_MS, lastEdit + IDLE_MS) - now())
	}

	fun stop() {
		cancel(tick)
		if (lease.getAndSet(false)) session?.send(false) { true }
		session = null
	}

	companion object {
		const val RENEW_MS = 3_500L
		const val IDLE_MS = 5_000L
	}
}
