package org.briarproject.briar.telegram

import org.briarproject.bramble.api.lifecycle.ServiceException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS

class TelegramTdlibAccessGate {

	private val lock = Any()
	@Volatile
	private var stopLatch = CountDownLatch(1)
	@Volatile
	private var acceptingCalls = true
	@Volatile
	private var unconfirmedClose = false

	val isStopped: Boolean
		get() = !acceptingCalls

	fun isAcceptingCalls(): Boolean = acceptingCalls

	fun hasUnconfirmedClose(): Boolean = unconfirmedClose

	fun <T> run(fallback: T, call: () -> T): T {
		if (!acceptingCalls) return fallback
		return synchronized(lock) {
			if (acceptingCalls) call() else fallback
		}
	}

	fun start() = synchronized(lock) {
		stopLatch = CountDownLatch(1)
		acceptingCalls = true
		unconfirmedClose = false
	}

	/**
	 * Records that a TDLib client could not confirm its closure before giving
	 * up. [stop] surfaces this as a failure so callers do not treat shutdown
	 * as safe to proceed with (e.g. account deletion) while that client may
	 * still own the database.
	 */
	fun reportUnconfirmedClose() {
		unconfirmedClose = true
	}

	/**
	 * Backward-compatible alias for [reportUnconfirmedClose] for cancellations
	 * during shutdown.
	 */
	fun reportUnconfirmedCancelledClose() {
		reportUnconfirmedClose()
	}

	@Throws(ServiceException::class)
	fun stop(close: () -> Unit) {
		acceptingCalls = false
		stopLatch.countDown()
		synchronized(lock) {
			close()
			if (unconfirmedClose) {
				unconfirmedClose = false
				throw ServiceException()
			}
		}
	}

	@Throws(InterruptedException::class)
	fun sleepUnlessStopped(timeoutMs: Long) {
		if (!acceptingCalls || timeoutMs <= 0L) return
		stopLatch.await(timeoutMs, MILLISECONDS)
	}

	@Throws(InterruptedException::class)
	fun awaitUnlessStopped(
		latch: CountDownLatch,
		timeoutMs: Long,
		cancellationGraceMs: Long = 200L,
	): Boolean {
		if (Thread.interrupted()) throw InterruptedException()
		if (!acceptingCalls || timeoutMs <= 0L) return false
		if (latch.count == 0L) return true
		val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
		var stopDeadlineNanos: Long? = null
		while (true) {
			if (!acceptingCalls && stopDeadlineNanos == null) {
				stopDeadlineNanos = System.nanoTime() + cancellationGraceMs * 1_000_000L
			}
			val effectiveDeadline = if (stopDeadlineNanos != null) {
				minOf(deadlineNanos, stopDeadlineNanos)
			} else {
				deadlineNanos
			}
			val remainingNanos = effectiveDeadline - System.nanoTime()
			if (remainingNanos <= 0L) return false
			val waitMs = minOf((remainingNanos + 999_999L) / 1_000_000L, 25L)
			if (latch.await(waitMs, MILLISECONDS)) return true
		}
	}
}
