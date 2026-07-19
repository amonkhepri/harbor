package org.briarproject.briar.telegram

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import java.lang.reflect.Proxy

internal class PendingAuthorizationUpdate(private val acceptedClassName: String? = null) {
	private val authorizationStateClassName = AtomicReference("")
	private val updateReceived = CountDownLatch(1)

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

	@Throws(InterruptedException::class)
	fun await(timeoutMs: Long): String? = if (updateReceived.await(timeoutMs, TimeUnit.MILLISECONDS)) {
		authorizationStateClassName.get()
	} else {
		null
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
internal fun createReflectiveTdlibClient(onUpdate: (Any?) -> Unit): Any {
	val clientClass = Class.forName("org.drinkless.tdlib.Client")
	val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
	val exceptionHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ExceptionHandler")
	val updateHandler = Proxy.newProxyInstance(
		resultHandlerClass.classLoader,
		arrayOf(resultHandlerClass),
	) { _, method, args ->
		if (method.name == "onResult" && args?.size == 1) onUpdate(args[0])
		null
	}
	val create = clientClass.getMethod(
		"create",
		resultHandlerClass,
		exceptionHandlerClass,
		exceptionHandlerClass,
	)
	return create.invoke(null, updateHandler, null, null)
}

@Throws(ReflectiveOperationException::class)
internal fun getAuthorizationStateClassName(update: Any?): String {
	if (update?.javaClass?.simpleName != "UpdateAuthorizationState") {
		return ""
	}
	val authorizationState = update.javaClass.getField("authorizationState").get(update)
	return authorizationState?.javaClass?.simpleName ?: ""
}

internal fun getTelegramCodeDeliveryStatus(update: Any?): String? {
	if (update?.javaClass?.simpleName != "UpdateAuthorizationState") return null
	return try {
		val authorizationState = update.javaClass.getField("authorizationState").get(update)
		if (authorizationState?.javaClass?.simpleName != "AuthorizationStateWaitCode") return null
		val codeInfo = authorizationState.javaClass.getField("codeInfo").get(authorizationState)
			?: return UNKNOWN_CODE_DELIVERY_STATUS
		val currentType = codeDeliveryType(codeInfo.javaClass.getField("type").get(codeInfo))
		val nextType = codeDeliveryType(codeInfo.javaClass.getField("nextType").get(codeInfo))
		val timeoutSeconds = codeInfo.javaClass.getField("timeout").getInt(codeInfo)
		"current=$currentType next=$nextType timeout_seconds=$timeoutSeconds"
	} catch (_: ReflectiveOperationException) {
		UNKNOWN_CODE_DELIVERY_STATUS
	}
}

private fun codeDeliveryType(type: Any?): String {
	if (type == null) return "NONE"
	return type.javaClass.simpleName
		.removePrefix("AuthenticationCodeType")
		.replace(CAMEL_CASE_BOUNDARY, "_")
		.uppercase(Locale.US)
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

private val CAMEL_CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
private const val UNKNOWN_CODE_DELIVERY_STATUS =
	"current=UNKNOWN next=UNKNOWN timeout_seconds=UNKNOWN"
