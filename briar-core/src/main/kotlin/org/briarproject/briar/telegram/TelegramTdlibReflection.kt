package org.briarproject.briar.telegram

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal class PendingAuthorizationUpdate(private val acceptedClassName: String? = null) {
	val authorizationStateClassName = AtomicReference("")
	val updateReceived = CountDownLatch(1)

	fun capture(className: String) {
		if (className.isEmpty() ||
			acceptedClassName != null &&
			className != acceptedClassName
		) {
			return
		}
		if (authorizationStateClassName.compareAndSet("", className)) {
			updateReceived.countDown()
		}
	}
}

internal fun tdlibClientClassExists(): Boolean = try {
	Class.forName(
		"org.drinkless.tdlib.Client",
		false,
		PendingAuthorizationUpdate::class.java.classLoader,
	)
	true
} catch (_: ClassNotFoundException) {
	false
} catch (_: LinkageError) {
	false
}

internal fun hasText(value: String): Boolean = value.trim().isNotEmpty()
