package org.drinkless.tdlib

@Suppress("unused")
abstract class TdApi {

	abstract class Function

	class Ok

	class Error

	class UpdateAuthorizationState(
		@JvmField val authorizationState: Any?,
	)

	class AuthorizationStateWaitTdlibParameters

	class AuthorizationStateWaitPhoneNumber

	class AuthorizationStateWaitCode

	class AuthorizationStateWaitPassword

	class AuthorizationStateReady

	class AuthorizationStateClosed

	class SetTdlibParameters : Function() {
		@JvmField var useTestDc: Boolean = false
		@JvmField var databaseDirectory: String? = null
		@JvmField var filesDirectory: String? = null
		@JvmField var databaseEncryptionKey: ByteArray? = null
		@JvmField var useFileDatabase: Boolean = false
		@JvmField var useChatInfoDatabase: Boolean = false
		@JvmField var useMessageDatabase: Boolean = false
		@JvmField var useSecretChats: Boolean = false
		@JvmField var apiId: Int = 0
		@JvmField var apiHash: String? = null
		@JvmField var systemLanguageCode: String? = null
		@JvmField var deviceModel: String? = null
		@JvmField var systemVersion: String? = null
		@JvmField var applicationVersion: String? = null
	}

	class PhoneNumberAuthenticationSettings

	class SetAuthenticationPhoneNumber(
		@JvmField val phoneNumber: String,
		@JvmField val settings: PhoneNumberAuthenticationSettings?,
	) : Function()

	class CheckAuthenticationCode(
		@JvmField val code: String,
	) : Function()

	class CheckAuthenticationPassword(
		@JvmField val password: String,
	) : Function()

	class Close : Function()
}
