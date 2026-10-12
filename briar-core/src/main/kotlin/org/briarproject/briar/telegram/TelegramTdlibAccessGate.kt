package org.briarproject.briar.telegram

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS

class TelegramTdlibAccessGate {

	private val lock = Any()
	@Volatile
	private var stopLatch = CountDownLatch(1)
	@Volatile
	private var acceptingCalls = true

	val isStopped: Boolean
		get() = !acceptingCalls

	fun isAcceptingCalls(): Boolean = acceptingCalls

	fun <T> run(fallback: T, call: () -> T): T {
		if (!acceptingCalls) return fallback
		return synchronized(lock) {
			if (acceptingCalls) call() else fallback
		}
	}

	fun start() = synchronized(lock) {
		stopLatch = CountDownLatch(1)
		acceptingCalls = true
	}

	fun stop(close: () -> Unit) {
		acceptingCalls = false
		stopLatch.countDown()
		synchronized(lock) {
			close()
		}
	}

	fun sleepUnlessStopped(timeoutMs: Long) {
		if (!acceptingCalls || timeoutMs <= 0L) return
		try {
			stopLatch.await(timeoutMs, MILLISECONDS)
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
		}
	}

	fun awaitUnlessStopped(
		latch: CountDownLatch,
		timeoutMs: Long,
		cancellationGraceMs: Long = 200L,
	): Boolean {
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
			try {
				if (latch.await(waitMs, MILLISECONDS)) return true
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
				return false
			}
		}
	}
}
