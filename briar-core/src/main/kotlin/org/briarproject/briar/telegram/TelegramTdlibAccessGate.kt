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
}
