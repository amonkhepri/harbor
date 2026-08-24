package org.briarproject.briar.telegram

import org.briarproject.briar.api.telegram.TelegramAuthSession
import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthSession.Snapshot
import org.briarproject.briar.api.telegram.TelegramAuthState
import org.briarproject.bramble.api.lifecycle.Service
import java.io.File
import java.util.logging.Logger

class TelegramAuthSessionImpl(
	private val tdlibLoginClient: TelegramTdlibLoginClient,
	private val accessGate: TelegramTdlibAccessGate = TelegramTdlibAccessGate(),
) : TelegramAuthSession,
	Service {

	@Volatile
	private var currentState = TelegramAuthState.CLOSED

	override fun getSnapshot(): Snapshot = accessGate.run(
		Snapshot(TelegramAuthState.CLOSED, RecoverableErrorDetail.NONE),
	) {
		Snapshot(
			currentState,
			tdlibLoginClient.getRecoverableErrorDetail(),
			tdlibLoginClient.getQrAuthorizationLink(),
		)
	}

	override fun start() = updateState(tdlibLoginClient::start)

	override fun submitIdentifier(identifier: String) =
		updateState { tdlibLoginClient.submitIdentifier(identifier) }

	override fun resendCode() = updateState(tdlibLoginClient::resendCode)

	override fun submitCode(code: String) = updateState { tdlibLoginClient.submitCode(code) }

	override fun submitPassword(password: String) =
		updateState { tdlibLoginClient.submitPassword(password) }

	override fun requestQrCodeAuthentication() =
		updateState(tdlibLoginClient::requestQrCodeAuthentication)

	override fun awaitQrAuthorizationUpdate() =
		updateState(tdlibLoginClient::awaitQrAuthorizationUpdate)

	override fun close() = updateState(tdlibLoginClient::close)

	private fun updateState(call: () -> TelegramAuthState) {
		accessGate.run(Unit) {
			currentState = call()
		}
	}

	override fun startService() = accessGate.start()

	override fun stopService() = accessGate.stop {
		currentState = tdlibLoginClient.closeForShutdown()
	}
}

class DisabledTelegramTdlibLoginClient : TelegramTdlibLoginClient {
	override fun start(): TelegramAuthState = TelegramAuthState.CLOSED

	override fun getRecoverableErrorDetail(): RecoverableErrorDetail = RecoverableErrorDetail.NONE

	override fun submitIdentifier(identifier: String): TelegramAuthState = TelegramAuthState.CLOSED

	override fun resendCode(): TelegramAuthState = TelegramAuthState.CLOSED

	override fun submitCode(code: String): TelegramAuthState = TelegramAuthState.CLOSED

	override fun submitPassword(password: String): TelegramAuthState = TelegramAuthState.CLOSED

	override fun requestQrCodeAuthentication(): TelegramAuthState = TelegramAuthState.CLOSED

	override fun awaitQrAuthorizationUpdate(): TelegramAuthState = TelegramAuthState.CLOSED

	override fun getQrAuthorizationLink(): String? = null

	override fun close(): TelegramAuthState = TelegramAuthState.CLOSED

	override fun closeForShutdown(): TelegramAuthState = TelegramAuthState.CLOSED
}

class ReflectiveTelegramTdlibLoginClient(
	private val tdlibDirectory: File = File("harbor-telegram"),
	private val apiId: Int = 0,
	private val apiHash: String = "",
	private val tdlibKeyProvider: TelegramTdlibDatabaseKeyProvider =
		NoOpTelegramTdlibDatabaseKeyProvider,
	private val authorizationUpdateTimeoutMs: Long = 30_000L,
	private val shutdownCloseTimeoutMs: Long = 60_000L,
	private val qrAuthorizationTimeoutMs: Long = 300_000L,
	private val qrAuthorizationPollMs: Long = 1_000L,
) : TelegramTdlibLoginClient {

	private val log = Logger.getLogger(ReflectiveTelegramTdlibLoginClient::class.java.name)

	private enum class CommandResult {
		OK,
		ERROR,
		TIMEOUT,
	}

	@Volatile
	private var lastAuthorizationStateClassName = ""

	@Volatile
	private var activeClientGeneration = 0L
	private var nextClientGeneration = 0L

	@Volatile
	private var pendingAuthorizationUpdate: PendingAuthorizationUpdate? = null

	@Volatile
	private var recoverableErrorDetail = RecoverableErrorDetail.NONE

	@Volatile
	private var qrAuthorizationLink: String? = null
	private var tdlibClient: Any? = null

	@Volatile
	private var qrAuthorizationDeadlineNanos = 0L

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
			ensureAtWaitPhoneNumber()?.let { return it }
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
			return recoverableErrorAfterClientFailure()
		} catch (e: LinkageError) {
			return recoverableErrorAfterClientFailure()
		} catch (e: InterruptedException) {
			return recoverableErrorAfterClientFailure(interrupted = true)
		}
	}

	@Throws(ReflectiveOperationException::class, InterruptedException::class)
	private fun ensureAtWaitPhoneNumber(allowQrChallenge: Boolean = false): TelegramAuthState? {
		if (lastAuthorizationStateClassName == "AuthorizationStateWaitTdlibParameters") {
			if (apiId <= 0 || !hasText(apiHash)) {
				return recoverableError(RecoverableErrorDetail.MISSING_API_CREDENTIALS)
			}
			val request = createTelegramTdlibParametersRequest(
				tdlibKeyProvider,
				tdlibDirectory,
				apiId,
				apiHash,
			) ?: return recoverableDatabaseKeyUnavailable()
			val update = prepareAuthorizationUpdate()
			when (sendForResult(request)) {
				CommandResult.OK -> Unit
				CommandResult.ERROR ->
					return recoverableError(RecoverableErrorDetail.TDLIB_DATABASE_KEY_MISMATCH)
				CommandResult.TIMEOUT -> return recoverableError(RecoverableErrorDetail.NONE)
			}
			val state = awaitPreparedAuthorizationStateClassName(update)
			if (state != "AuthorizationStateWaitPhoneNumber" &&
				(!allowQrChallenge || state != QR_AUTHORIZATION_STATE)
			) {
				return recoverablePersistedSessionIdentityUnverified()
			}
		}
		return when {
			lastAuthorizationStateClassName == "AuthorizationStateWaitPhoneNumber" -> null
			allowQrChallenge && lastAuthorizationStateClassName == QR_AUTHORIZATION_STATE ->
				mapAuthorizationStateClassName(lastAuthorizationStateClassName)
			else -> recoverableError(RecoverableErrorDetail.NONE)
		}
	}

	override fun submitCode(code: String): TelegramAuthState = submitCredential(
		credential = code,
		expectedAuthorizationState = "AuthorizationStateWaitCode",
		createRequest = ::createCheckAuthenticationCodeRequest,
		invalidDetail = RecoverableErrorDetail.INVALID_CODE,
	)

	override fun resendCode(): TelegramAuthState {
		if (tdlibClient == null || lastAuthorizationStateClassName != "AuthorizationStateWaitCode") {
			return recoverableError(RecoverableErrorDetail.NONE)
		}
		return try {
			val authorizationUpdate = prepareAuthorizationUpdate()
			when (sendForResult(createTdApiObject("ResendAuthenticationCode"))) {
				CommandResult.OK -> mapAuthorizationStateClassName(
					awaitPreparedAuthorizationStateClassName(authorizationUpdate),
				)
				CommandResult.ERROR -> {
					if (pendingAuthorizationUpdate === authorizationUpdate) {
						pendingAuthorizationUpdate = null
					}
					recoverableError(RecoverableErrorDetail.CODE_RESEND_FAILED)
				}
				CommandResult.TIMEOUT -> recoverableError(RecoverableErrorDetail.NONE)
			}
		} catch (e: ReflectiveOperationException) {
			recoverableErrorAfterClientFailure()
		} catch (e: LinkageError) {
			recoverableErrorAfterClientFailure()
		} catch (e: InterruptedException) {
			recoverableErrorAfterClientFailure(interrupted = true)
		}
	}

	override fun submitPassword(password: String): TelegramAuthState = submitCredential(
		credential = password,
		expectedAuthorizationState = "AuthorizationStateWaitPassword",
		createRequest = ::createCheckAuthenticationPasswordRequest,
		invalidDetail = RecoverableErrorDetail.INVALID_PASSWORD,
	)

	override fun getQrAuthorizationLink(): String? = qrAuthorizationLink

	override fun requestQrCodeAuthentication(): TelegramAuthState {
		if (tdlibClient == null || lastAuthorizationStateClassName == "AuthorizationStateClosed") {
			val state = start()
			if (state != TelegramAuthState.IDENTIFIER_ENTRY) return state
		}
		return try {
			val state = ensureAtWaitPhoneNumber(allowQrChallenge = true)
				?: requestQrAuthorization()
			if (state == TelegramAuthState.QR_WAITING) {
				qrAuthorizationDeadlineNanos = System.nanoTime() + qrAuthorizationTimeoutMs * NANOS_PER_MS
			}
			state
		} catch (e: ReflectiveOperationException) {
			recoverableErrorAfterClientFailure()
		} catch (e: LinkageError) {
			recoverableErrorAfterClientFailure()
		} catch (e: InterruptedException) {
			recoverableErrorAfterClientFailure(interrupted = true)
		}
	}

	@Throws(ReflectiveOperationException::class, InterruptedException::class)
	private fun requestQrAuthorization(): TelegramAuthState {
		val update = prepareAuthorizationUpdate()
		return when (sendForResult(createTdApiObject("RequestQrCodeAuthentication"))) {
			CommandResult.OK ->
				mapAuthorizationStateClassName(awaitPreparedAuthorizationStateClassName(update))
			CommandResult.ERROR -> recoverableError(RecoverableErrorDetail.NONE)
			CommandResult.TIMEOUT -> recoverableError(RecoverableErrorDetail.NONE)
		}
	}

	override fun awaitQrAuthorizationUpdate(): TelegramAuthState {
		val observedState = lastAuthorizationStateClassName
		if (observedState != QR_AUTHORIZATION_STATE) {
			return mapAuthorizationStateClassName(observedState)
		}
		if (qrAuthorizationDeadlineNanos == 0L) {
			qrAuthorizationDeadlineNanos = System.nanoTime() + qrAuthorizationTimeoutMs * NANOS_PER_MS
		}
		val remainingNanos = qrAuthorizationDeadlineNanos - System.nanoTime()
		if (remainingNanos <= 0L) return recoverableErrorAfterClientFailure()
		val waitMs = minOf(
			qrAuthorizationPollMs,
			(remainingNanos / NANOS_PER_MS).coerceAtLeast(1L),
		)
		return try {
			val state = prepareAuthorizationUpdate().await(waitMs)
			if (state == null) {
				if (System.nanoTime() >= qrAuthorizationDeadlineNanos) {
					recoverableErrorAfterClientFailure()
				} else {
					clearRecoverableErrorDetail(TelegramAuthState.QR_WAITING)
				}
			} else {
				lastAuthorizationStateClassName = state
				mapAuthorizationStateClassName(state).also {
					if (it != TelegramAuthState.QR_WAITING) qrAuthorizationDeadlineNanos = 0L
				}
			}
		} catch (e: InterruptedException) {
			recoverableErrorAfterClientFailure(interrupted = true)
		}
	}

	private fun submitCredential(
		credential: String,
		expectedAuthorizationState: String,
		createRequest: (String) -> Any,
		invalidDetail: RecoverableErrorDetail,
	): TelegramAuthState {
		if (!hasText(credential) ||
			tdlibClient == null ||
			lastAuthorizationStateClassName != expectedAuthorizationState
		) {
			return recoverableError(RecoverableErrorDetail.NONE)
		}
		try {
			val authorizationUpdate = prepareAuthorizationUpdate()
			when (sendForResult(createRequest(credential))) {
				CommandResult.OK -> Unit
				CommandResult.ERROR ->
					return recoverableError(invalidDetail)
				CommandResult.TIMEOUT ->
					return recoverableError(RecoverableErrorDetail.NONE)
			}
			return mapAuthorizationStateClassName(
				awaitPreparedAuthorizationStateClassName(authorizationUpdate),
			)
		} catch (e: ReflectiveOperationException) {
			return recoverableErrorAfterClientFailure()
		} catch (e: LinkageError) {
			return recoverableErrorAfterClientFailure()
		} catch (e: InterruptedException) {
			return recoverableErrorAfterClientFailure(interrupted = true)
		}
	}

	override fun close(): TelegramAuthState {
		closeTdlibClient()
		return clearRecoverableErrorDetail(TelegramAuthState.CLOSED)
	}

	override fun closeForShutdown(): TelegramAuthState =
		if (closeTdlibClient(waitUntilConfirmed = true)) {
			clearRecoverableErrorDetail(TelegramAuthState.CLOSED)
		} else {
			recoverableError(RecoverableErrorDetail.NONE)
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
		timeoutMs: Long = authorizationUpdateTimeoutMs,
	): String = (pendingAuthorizationUpdate.await(timeoutMs) ?: "").also {
		lastAuthorizationStateClassName = it
	}

	@Throws(ReflectiveOperationException::class)
	private fun createTdlibClient(): Any {
		val clientGeneration = ++nextClientGeneration
		activeClientGeneration = clientGeneration
		return createReflectiveTdlibClient { update ->
			val pendingAuthorizationUpdate = pendingAuthorizationUpdate
			val className = getAuthorizationStateClassName(update)
			if (clientGeneration != activeClientGeneration) {
				if (pendingAuthorizationUpdate?.acceptsOnly(className) == true) {
					pendingAuthorizationUpdate.capture(className)
				}
				return@createReflectiveTdlibClient
			}
			getQrAuthorizationLink(update)?.let { qrAuthorizationLink = it }
			if (qrAuthorizationDeadlineNanos != 0L && className.isNotEmpty()) {
				lastAuthorizationStateClassName = className
			}
			if (pendingAuthorizationUpdate == null) return@createReflectiveTdlibClient
			getTelegramCodeDeliveryStatus(update)?.let {
				log.info("Telegram auth code delivery: $it")
			}
			pendingAuthorizationUpdate.capture(className)
		}
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
		val result = sendReflectiveTdlibRequest(
			tdlibClient!!,
			request,
			authorizationUpdateTimeoutMs,
		)
		if (!result.completed) {
			closeTdlibClient()
			return CommandResult.TIMEOUT
		}
		return if (result.value?.javaClass?.simpleName == "Error"
		) {
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
			QR_AUTHORIZATION_STATE ->
				clearRecoverableErrorDetail(TelegramAuthState.QR_WAITING)
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

	private fun recoverableErrorAfterClientFailure(interrupted: Boolean = false): TelegramAuthState {
		if (interrupted) Thread.currentThread().interrupt()
		closeTdlibClient()
		return recoverableError(RecoverableErrorDetail.NONE)
	}

	private fun recoverableDatabaseKeyUnavailable(): TelegramAuthState {
		closeTdlibClient()
		val detail = if (tdlibKeyProvider.isKeyStrengtheningAvailable()) {
			RecoverableErrorDetail.NONE
		} else {
			RecoverableErrorDetail.DEVICE_KEYSTORE_UNAVAILABLE
		}
		return recoverableError(detail)
	}

	private fun recoverablePersistedSessionIdentityUnverified(): TelegramAuthState {
		closeTdlibClient()
		return recoverableError(RecoverableErrorDetail.PERSISTED_SESSION_IDENTITY_UNVERIFIED)
	}

	private fun closeTdlibClient(waitUntilConfirmed: Boolean = false): Boolean {
		activeClientGeneration = ++nextClientGeneration
		lastAuthorizationStateClassName = ""
		qrAuthorizationLink = null
		qrAuthorizationDeadlineNanos = 0L
		completePendingAuthorizationUpdate("AuthorizationStateClosed")
		val client = tdlibClient ?: return true
		tdlibClient = null
		val closeUpdate = prepareAuthorizationUpdate("AuthorizationStateClosed")
		val shutdownDeadlineNanos = if (waitUntilConfirmed) {
			System.nanoTime() + shutdownCloseTimeoutMs * NANOS_PER_MS
		} else {
			0L
		}
		var interrupted = false
		var confirmed = false
		try {
			closeReflectiveTdlibClient(client)
			do {
				val timeoutMs = if (waitUntilConfirmed) {
					val remainingNanos = shutdownDeadlineNanos - System.nanoTime()
					if (remainingNanos <= 0L) break
					minOf(
						maxOf(1L, authorizationUpdateTimeoutMs),
						(remainingNanos + NANOS_PER_MS - 1L) / NANOS_PER_MS,
					)
				} else {
					authorizationUpdateTimeoutMs
				}
				confirmed = try {
					awaitAuthorizationUpdate(closeUpdate, timeoutMs).isNotEmpty()
				} catch (_: InterruptedException) {
					interrupted = true
					false
				}
			} while (waitUntilConfirmed && !confirmed)
		} catch (_: ReflectiveOperationException) {
		} catch (_: LinkageError) {
		} finally {
			if (interrupted) Thread.currentThread().interrupt()
			if (pendingAuthorizationUpdate === closeUpdate) {
				pendingAuthorizationUpdate = null
			}
		}
		if (waitUntilConfirmed && !confirmed) {
			log.warning("Telegram TDLib close was not confirmed before shutdown timeout")
		}
		return confirmed
	}

	private fun completePendingAuthorizationUpdate(className: String) {
		pendingAuthorizationUpdate?.let {
			it.capture(className)
		}
	}

	private companion object {
		const val QR_AUTHORIZATION_STATE = "AuthorizationStateWaitOtherDeviceConfirmation"
		const val NANOS_PER_MS = 1_000_000L
	}
}
