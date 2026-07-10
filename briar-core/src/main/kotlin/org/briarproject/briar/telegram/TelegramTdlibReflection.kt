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

@Throws(ReflectiveOperationException::class)
internal fun getAuthorizationStateClassName(update: Any?): String {
	if (update?.javaClass?.simpleName != "UpdateAuthorizationState") {
		return ""
	}
	val authorizationState = update.javaClass.getField("authorizationState").get(update)
	return authorizationState?.javaClass?.simpleName ?: ""
}

@Throws(ReflectiveOperationException::class)
internal fun tdApiClass(className: String): Class<*> =
	Class.forName("org.drinkless.tdlib.TdApi\$$className")

@Throws(ReflectiveOperationException::class)
internal fun createTdApiObject(className: String): Any = tdApiClass(className)
	.getConstructor()
	.newInstance()

@Throws(ReflectiveOperationException::class)
internal fun setFieldIfPresent(target: Any, name: String, value: Any?) {
	try {
		target.javaClass.getField(name).set(target, value)
	} catch (_: NoSuchFieldException) {
	}
}

internal fun hasText(value: String): Boolean = value.trim().isNotEmpty()
