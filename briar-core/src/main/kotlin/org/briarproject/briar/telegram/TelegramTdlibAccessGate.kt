package org.briarproject.briar.telegram

class TelegramTdlibAccessGate {

	private val lock = Any()
	private var acceptingCalls = true

	fun <T> run(fallback: T, call: () -> T): T = synchronized(lock) {
		if (acceptingCalls) call() else fallback
	}

	fun start() = synchronized(lock) {
		acceptingCalls = true
	}

	fun stop(close: () -> Unit) = synchronized(lock) {
		acceptingCalls = false
		close()
	}
}
