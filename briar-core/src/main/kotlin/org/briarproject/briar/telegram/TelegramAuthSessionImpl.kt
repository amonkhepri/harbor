package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramAuthSession
import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthState
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TelegramAuthSessionImpl(private val tdlibLoginClient: TelegramTdlibLoginClient) :
	TelegramAuthSession {

	private var currentState = TelegramAuthState.CLOSED

	override fun getCurrentState(): TelegramAuthState = currentState

	override fun getRecoverableErrorDetail(): RecoverableErrorDetail =
		tdlibLoginClient.getRecoverableErrorDetail()

	override fun start() {
		currentState = tdlibLoginClient.start()
	}

	override fun submitIdentifier(identifier: String) {
		currentState = tdlibLoginClient.submitIdentifier(identifier)
	}

	override fun submitCode(code: String) {
		currentState = tdlibLoginClient.submitCode(code)
	}

	override fun submitPassword(password: String) {
		currentState = tdlibLoginClient.submitPassword(password)
	}

	override fun close() {
		currentState = tdlibLoginClient.close()
	}
}

class NoOpTelegramTdlibLoginClient : TelegramTdlibLoginClient {
	override fun start(): TelegramAuthState = TelegramAuthState.CLOSED

	override fun getRecoverableErrorDetail(): RecoverableErrorDetail = RecoverableErrorDetail.NONE

	override fun submitIdentifier(identifier: String): TelegramAuthState = TelegramAuthState.CLOSED

	override fun submitCode(code: String): TelegramAuthState = TelegramAuthState.CLOSED

	override fun submitPassword(password: String): TelegramAuthState = TelegramAuthState.CLOSED

	override fun close(): TelegramAuthState = TelegramAuthState.CLOSED
}

class ReflectiveTelegramTdlibLoginClient @JvmOverloads constructor(
	private val tdlibDirectory: File = File("harbor-telegram"),
	private val apiId: Int = 0,
	private val apiHash: String = "",
	private val tdlibKeyProvider: TelegramTdlibDatabaseKeyProvider =
		NoOpTelegramTdlibDatabaseKeyProvider,
	private val authorizationUpdateTimeoutMs: Long = 30_000L,
) : TelegramTdlibLoginClient {

	private enum class CommandResult {
		OK,
		ERROR,
		TIMEOUT,
	}

	private var lastAuthorizationStateClassName = ""

	@Volatile
	private var activeClientGeneration = 0L
	private var nextClientGeneration = 0L

	@Volatile
	private var pendingAuthorizationUpdate: PendingAuthorizationUpdate? = null
	private var recoverableErrorDetail = RecoverableErrorDetail.NONE
	private var tdlibClient: Any? = null

	override fun start(): TelegramAuthState {
		closeTdlibClient()
		if (!tdlibClientClassExists()) {
			return recoverableError(RecoverableErrorDetail.MISSING_TDLIB)
		}
		return mapAuthorizationStateClassName(awaitAuthorizationStateClassName(true))
	}

	override fun getRecoverableErrorDetail(): RecoverableErrorDetail = recoverableErrorDetail

	override fun submitIdentifier(identifier: String): TelegramAuthState {
		if (!hasText(identifier)) {
			return recoverableError(RecoverableErrorDetail.NONE)
		}
		if (tdlibClient == null) {
			val restartedState = start()
			if (restartedState != TelegramAuthState.IDENTIFIER_ENTRY) return restartedState
		}
		try {
			if (lastAuthorizationStateClassName == "AuthorizationStateWaitTdlibParameters") {
				if (apiId <= 0 || !hasText(apiHash)) {
					return recoverableError(RecoverableErrorDetail.MISSING_API_CREDENTIALS)
				}
				val parametersRequest = createTelegramTdlibParametersRequest(
					tdlibKeyProvider,
					tdlibDirectory,
					apiId,
					apiHash,
				) ?: return recoverableDatabaseKeyUnavailable()
				val tdlibParametersUpdate = prepareAuthorizationUpdate()
				when (sendForResult(parametersRequest)) {
					CommandResult.OK -> Unit
					CommandResult.ERROR ->
						return recoverableError(RecoverableErrorDetail.MISSING_API_CREDENTIALS)
					CommandResult.TIMEOUT ->
						return recoverableError(RecoverableErrorDetail.NONE)
				}
				val authorizationStateAfterParameters =
					awaitPreparedAuthorizationStateClassName(tdlibParametersUpdate)
				if (authorizationStateAfterParameters != "AuthorizationStateWaitPhoneNumber") {
					return mapAuthorizationStateClassName(authorizationStateAfterParameters)
				}
			}
			if (lastAuthorizationStateClassName != "AuthorizationStateWaitPhoneNumber") {
				return recoverableError(RecoverableErrorDetail.NONE)
			}
			val phoneNumberUpdate = prepareAuthorizationUpdate()
			when (sendForResult(createSetAuthenticationPhoneNumberRequest(identifier))) {
				CommandResult.OK -> Unit
				CommandResult.ERROR ->
					return recoverableError(RecoverableErrorDetail.INVALID_IDENTIFIER)
				CommandResult.TIMEOUT ->
					return recoverableError(RecoverableErrorDetail.NONE)
			}
			return mapAuthorizationStateClassName(
				awaitPreparedAuthorizationStateClassName(phoneNumberUpdate),
			)
		} catch (e: ReflectiveOperationException) {
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		} catch (e: LinkageError) {
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		}
	}

	override fun submitCode(code: String): TelegramAuthState {
		if (!hasText(code) ||
			tdlibClient == null ||
			lastAuthorizationStateClassName != "AuthorizationStateWaitCode"
		) {
			return recoverableError(RecoverableErrorDetail.NONE)
		}
		try {
			val codeUpdate = prepareAuthorizationUpdate()
			when (sendForResult(createCheckAuthenticationCodeRequest(code))) {
				CommandResult.OK -> Unit
				CommandResult.ERROR ->
					return recoverableError(RecoverableErrorDetail.INVALID_CODE)
				CommandResult.TIMEOUT ->
					return recoverableError(RecoverableErrorDetail.NONE)
			}
			return mapAuthorizationStateClassName(awaitPreparedAuthorizationStateClassName(codeUpdate))
		} catch (e: ReflectiveOperationException) {
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		} catch (e: LinkageError) {
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		}
	}

	override fun submitPassword(password: String): TelegramAuthState {
		if (!hasText(password) ||
			tdlibClient == null ||
			lastAuthorizationStateClassName != "AuthorizationStateWaitPassword"
		) {
			return recoverableError(RecoverableErrorDetail.NONE)
		}
		try {
			val passwordUpdate = prepareAuthorizationUpdate()
			when (sendForResult(createCheckAuthenticationPasswordRequest(password))) {
				CommandResult.OK -> Unit
				CommandResult.ERROR ->
					return recoverableError(RecoverableErrorDetail.INVALID_PASSWORD)
				CommandResult.TIMEOUT ->
					return recoverableError(RecoverableErrorDetail.NONE)
			}
			return mapAuthorizationStateClassName(
				awaitPreparedAuthorizationStateClassName(passwordUpdate),
			)
		} catch (e: ReflectiveOperationException) {
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		} catch (e: LinkageError) {
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			closeTdlibClient()
			return recoverableError(RecoverableErrorDetail.NONE)
		}
	}

	override fun close(): TelegramAuthState {
		closeTdlibClient()
		return clearRecoverableErrorDetail(TelegramAuthState.CLOSED)
	}

	private fun awaitAuthorizationStateClassName(createClient: Boolean): String {
		val pendingAuthorizationUpdate = prepareAuthorizationUpdate()
		return try {
			if (createClient) tdlibClient = createTdlibClient()
			awaitPreparedAuthorizationStateClassName(pendingAuthorizationUpdate)
		} catch (e: ReflectiveOperationException) {
			closeTdlibClient()
			""
		} catch (e: LinkageError) {
			closeTdlibClient()
			""
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			closeTdlibClient()
			""
		}
	}

	private fun prepareAuthorizationUpdate(
		acceptedClassName: String? = null,
	): PendingAuthorizationUpdate = PendingAuthorizationUpdate(acceptedClassName).also {
		pendingAuthorizationUpdate = it
	}

	@Throws(InterruptedException::class)
	private fun awaitPreparedAuthorizationStateClassName(
		pendingAuthorizationUpdate: PendingAuthorizationUpdate,
	): String {
		if (tdlibClient == null) {
			closeTdlibClient()
			return ""
		}
		return awaitAuthorizationUpdate(pendingAuthorizationUpdate).also {
			if (it.isEmpty()) closeTdlibClient()
		}
	}

	@Throws(InterruptedException::class)
	private fun awaitAuthorizationUpdate(
		pendingAuthorizationUpdate: PendingAuthorizationUpdate,
	): String {
		if (!pendingAuthorizationUpdate.updateReceived.await(
				authorizationUpdateTimeoutMs,
				TimeUnit.MILLISECONDS,
			)
		) {
			return ""
		}
		return pendingAuthorizationUpdate.authorizationStateClassName.get().also {
			lastAuthorizationStateClassName = it
		}
	}

	@Throws(ReflectiveOperationException::class)
	private fun createTdlibClient(): Any {
		val clientGeneration = ++nextClientGeneration
		activeClientGeneration = clientGeneration
		val clientClass = Class.forName("org.drinkless.tdlib.Client")
		val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
		val exceptionHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ExceptionHandler")
		val updateHandler = Proxy.newProxyInstance(
			resultHandlerClass.classLoader,
			arrayOf(resultHandlerClass),
		) { _, method, args ->
			val pendingAuthorizationUpdate = pendingAuthorizationUpdate
			if (clientGeneration == activeClientGeneration &&
				method.name == "onResult" &&
				args != null &&
				args.size == 1 &&
				pendingAuthorizationUpdate != null
			) {
				val className = getAuthorizationStateClassName(args[0])
				pendingAuthorizationUpdate.capture(className)
			}
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
	private fun createSetAuthenticationPhoneNumberRequest(identifier: String): Any {
		val settingsClass = tdApiClass("PhoneNumberAuthenticationSettings")
		val settings = settingsClass.getConstructor().newInstance()
		return tdApiClass("SetAuthenticationPhoneNumber")
			.getConstructor(String::class.java, settingsClass)
			.newInstance(identifier, settings)
	}

	@Throws(ReflectiveOperationException::class)
	private fun createCheckAuthenticationCodeRequest(code: String): Any =
		tdApiClass("CheckAuthenticationCode")
			.getConstructor(String::class.java)
			.newInstance(code)

	@Throws(ReflectiveOperationException::class)
	private fun createCheckAuthenticationPasswordRequest(password: String): Any =
		tdApiClass("CheckAuthenticationPassword")
			.getConstructor(String::class.java)
			.newInstance(password)

	@Throws(ReflectiveOperationException::class, InterruptedException::class)
	private fun sendForResult(request: Any): CommandResult {
		val resultClassName = AtomicReference("")
		val resultReceived = CountDownLatch(1)
		val functionClass = tdApiClass("Function")
		val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
		val resultHandler = Proxy.newProxyInstance(
			resultHandlerClass.classLoader,
			arrayOf(resultHandlerClass),
		) { _, method, args ->
			if (method.name == "onResult" &&
				args != null &&
				args.size == 1 &&
				resultClassName.compareAndSet("", args[0]?.javaClass?.simpleName ?: "")
			) {
				resultReceived.countDown()
			}
			null
		}
		tdlibClient!!.javaClass.getMethod("send", functionClass, resultHandlerClass)
			.invoke(tdlibClient, request, resultHandler)
		if (!resultReceived.await(authorizationUpdateTimeoutMs, TimeUnit.MILLISECONDS)) {
			closeTdlibClient()
			return CommandResult.TIMEOUT
		}
		return if (resultClassName.get() == "Error") {
			CommandResult.ERROR
		} else {
			CommandResult.OK
		}
	}

	private fun mapAuthorizationStateClassName(className: String): TelegramAuthState =
		when (className) {
			"AuthorizationStateWaitTdlibParameters",
			"AuthorizationStateWaitPhoneNumber",
			-> clearRecoverableErrorDetail(
				TelegramAuthState.IDENTIFIER_ENTRY,
			)
			"AuthorizationStateWaitCode" -> clearRecoverableErrorDetail(TelegramAuthState.CODE_ENTRY)
			"AuthorizationStateWaitPassword" -> clearRecoverableErrorDetail(TelegramAuthState.PASSWORD_ENTRY)
			"AuthorizationStateReady" -> clearRecoverableErrorDetail(TelegramAuthState.READY)
			"AuthorizationStateClosed" -> clearRecoverableErrorDetail(TelegramAuthState.CLOSED)
			else -> {
				closeTdlibClient()
				recoverableError(recoverableDetailForUnsupportedState(className))
			}
		}

	private fun recoverableDetailForUnsupportedState(className: String): RecoverableErrorDetail =
		if (className.startsWith("AuthorizationStateWait")) {
			RecoverableErrorDetail.UNSUPPORTED_AUTH_STEP
		} else {
			RecoverableErrorDetail.NONE
		}

	private fun clearRecoverableErrorDetail(state: TelegramAuthState): TelegramAuthState {
		recoverableErrorDetail = RecoverableErrorDetail.NONE
		return state
	}

	private fun recoverableError(detail: RecoverableErrorDetail): TelegramAuthState {
		recoverableErrorDetail = detail
		return TelegramAuthState.RECOVERABLE_ERROR
	}

	private fun recoverableDatabaseKeyUnavailable(): TelegramAuthState {
		closeTdlibClient()
		return recoverableError(RecoverableErrorDetail.NONE)
	}

	private fun closeTdlibClient() {
		lastAuthorizationStateClassName = ""
		completePendingAuthorizationUpdate("AuthorizationStateClosed")
		val client = tdlibClient ?: return
		tdlibClient = null
		val closeUpdate = prepareAuthorizationUpdate("AuthorizationStateClosed")
		try {
			val functionClass = tdApiClass("Function")
			val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
			val send: Method = client.javaClass.getMethod("send", functionClass, resultHandlerClass)
			val closeRequest = createTdApiObject("Close")
			send.invoke(client, closeRequest, null)
			awaitAuthorizationUpdate(closeUpdate)
		} catch (_: ReflectiveOperationException) {
		} catch (_: LinkageError) {
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
		} finally {
			if (pendingAuthorizationUpdate === closeUpdate) {
				pendingAuthorizationUpdate = null
			}
		}
	}

	private fun completePendingAuthorizationUpdate(className: String) {
		pendingAuthorizationUpdate?.let {
			it.capture(className)
		}
	}
}
