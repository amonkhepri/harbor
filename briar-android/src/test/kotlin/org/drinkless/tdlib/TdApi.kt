package org.drinkless.tdlib

@Suppress("unused")
abstract class TdApi {

	abstract class Function

	class Ok

	class Error

	class Chat {
		@JvmField var id: Long = 0L

		@JvmField var title: String? = null

		@JvmField var lastMessage: Message? = null
	}

	class Chats {
		@JvmField var totalCount: Int = 0

		@JvmField var chatIds: LongArray = LongArray(0)
	}

	class FormattedText {
		@JvmField var text: String? = null
	}

	open class MessageContent

	class MessageText : MessageContent() {
		@JvmField var text: FormattedText? = null
	}

	class MessagePhoto : MessageContent()

	class Message {
		@JvmField var id: Long = 0L

		@JvmField var chatId: Long = 0L

		@JvmField var date: Int = 0

		@JvmField var isOutgoing: Boolean = false

		@JvmField var content: MessageContent? = null
	}

	class Messages {
		@JvmField var totalCount: Int = 0

		@JvmField var messages: Array<Message?> = emptyArray()
	}

	class UpdateAuthorizationState(@JvmField val authorizationState: Any?)

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

	class CheckAuthenticationCode(@JvmField val code: String) : Function()

	class CheckAuthenticationPassword(@JvmField val password: String) : Function()

	class GetChats : Function() {
		@JvmField var chatList: Any? = null

		@JvmField var limit: Int = 0
	}

	class LoadChats : Function() {
		@JvmField var chatList: Any? = null

		@JvmField var limit: Int = 0
	}

	class GetChat : Function() {
		@JvmField var chatId: Long = 0L
	}

	class GetChatHistory : Function() {
		@JvmField var chatId: Long = 0L

		@JvmField var fromMessageId: Long = 0L

		@JvmField var offset: Int = 0

		@JvmField var limit: Int = 0

		@JvmField var onlyLocal: Boolean = false
	}

	class Close : Function()
}
