package org.matrix.rustcomponents.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Minimal same-FQN fakes for the pinned `org.matrix.rustcomponents:sdk-android`
 * types [ReflectiveMatrixHomeserverDiscoveryClient][org.briarproject.briar.matrix.ReflectiveMatrixHomeserverDiscoveryClient]
 * resolves via reflection. Mirrors `org.drinkless.tdlib.Client.kt`'s same-package
 * test double for TDLib: only compiled into `:briar-android`'s test source set, so
 * it does not collide with the real AAR, which is only pulled onto any classpath
 * when `harbor.matrixConnector.enabled=true` (default test runs leave it off).
 *
 * The shapes below match the pinned `sdk-android:26.08.05` AAR's decompiled public
 * surface: `ClientBuilder.sqliteStore(SqliteStoreBuilder)`, not a `sqlitePath`/
 * `passphrase` pair on `ClientBuilder` itself; `SqliteStoreBuilder(dataPath,
 * cachePath).passphrase(String)`; `Client.session()` throwing [ClientException]
 * until a session exists; and `Client.restoreSession(Session, Continuation)`.
 */
sealed class ClientBuildException(message: String) : Exception(message) {
	class InvalidServerName : ClientBuildException("invalid server name")
	class ServerUnreachable : ClientBuildException("server unreachable")
	class Generic : ClientBuildException("generic failure")
}

/**
 * Mirrors the pinned AAR's real shape: `ClientException` is an abstract sealed base with no
 * public constructor, and `ClientException.Generic(msg, details)` is the concrete variant this
 * fake exercises. Its interface method's compiled bytecode carries no `Exceptions` attribute
 * (`javap -v` on the pinned AAR confirms no `throws` clause), so this stays a plain checked
 * [Exception], not `@Throws`-annotated here — annotating it would make the fake claim a
 * declared-throws contract the real interface does not have.
 */
sealed class ClientException(message: String) : Exception(message) {
	class Generic(val msg: String, val details: String) : ClientException(msg)
}

enum class SlidingSyncVersion { NONE, NATIVE }

enum class EncryptionState { NOT_ENCRYPTED, ENCRYPTED, UNKNOWN }

enum class Membership { INVITED, JOINED, LEFT, KNOCKED, BANNED }

enum class ReceiptType { READ, READ_PRIVATE, FULLY_READ }

class RoomInfo(val numUnreadMessages: ULong = 0uL, val isMarkedUnread: Boolean = false) {
	fun destroy() {
		FakeMatrixSdkState.roomInfoDestroyCount++
	}
}

/** Mirrors the pinned AAR's real shape: synchronous `id()`/`displayName()`/`isSpace()`/`membership()`. */
class Room(
	private val roomId: String,
	private val roomDisplayName: String?,
	private val space: Boolean,
	private val roomMembership: Membership,
	private val unreadMessagesCount: ULong? = null,
	private val roomTopic: String? = null,
) {
	fun id(): String = roomId
	fun displayName(): String? = roomDisplayName
	fun topic(): String? = roomTopic
	fun isSpace(): Boolean = space
	fun membership(): Membership = roomMembership
	fun encryptionState(): EncryptionState = FakeMatrixSdkState.encryptionState

	suspend fun roomInfo(): RoomInfo {
		FakeMatrixSdkState.roomInfoCallCount++
		FakeMatrixSdkState.roomInfoFailureToThrow?.let { throw it }
		val count = unreadMessagesCount ?: throw ClientException.Generic("no roomInfo", "no roomInfo")
		return RoomInfo(count, FakeMatrixSdkState.markedUnreadRooms[roomId] == true)
	}

	suspend fun timeline(): Timeline {
		FakeMatrixSdkState.timelineCallCount++
		val timeline = Timeline()
		if (!FakeMatrixSdkState.timelineSuspendAsynchronously) return timeline
		return suspendCoroutine { continuation ->
			Thread {
				if (FakeMatrixSdkState.timelineDelayMs > 0) {
					Thread.sleep(FakeMatrixSdkState.timelineDelayMs)
				}
				continuation.resume(timeline)
			}.start()
		}
	}

	fun close() {
		FakeMatrixSdkState.roomCloseCount++
		FakeMatrixSdkState.closedRoomHandles.add(roomId)
	}

	suspend fun markAsRead(receiptType: ReceiptType) {
		FakeMatrixSdkState.markAsReadCallCount++
		FakeMatrixSdkState.lastMarkAsReadRoomId = roomId
		FakeMatrixSdkState.lastMarkAsReadReceiptType = receiptType
		FakeMatrixSdkState.markAsReadFailureToThrow?.let { throw it }
	}

	suspend fun typingNotice(typing: Boolean) {
		FakeMatrixSdkState.typingCalls.add(typing)
		FakeMatrixSdkState.typingOperation(typing)
	}

	fun subscribeToTypingNotifications(listener: TypingNotificationsListener): TaskHandle {
		FakeMatrixSdkState.typingListenerAddCount++
		synchronized(FakeMatrixSdkState.activeTypingListeners) {
			FakeMatrixSdkState.activeTypingListeners.add(listener)
		}
		return TaskHandle {
			synchronized(FakeMatrixSdkState.activeTypingListeners) {
				FakeMatrixSdkState.activeTypingListeners.remove(listener)
			}
		}
	}

	suspend fun setUnreadFlag(newValue: Boolean) {
		FakeMatrixSdkState.markedUnreadRooms[roomId] = newValue
	}

	suspend fun leave() {
		FakeMatrixSdkState.leaveCalls++
		if (FakeMatrixSdkState.leaveFails) throw ClientException.Generic("failed", "failed")
		FakeMatrixSdkState.roomsToReturn = FakeMatrixSdkState.roomsToReturn.filter { it.roomId != roomId }
	}
}

interface TimelineListener {
	fun onUpdate(diffs: List<TimelineDiff>)
}

interface TypingNotificationsListener {
	fun call(typingUserIds: List<String>)
}

class Timeline {
	suspend fun addListener(listener: TimelineListener): TaskHandle {
		FakeMatrixSdkState.listenerAddCount++
		FakeMatrixSdkState.timelineOperationOrder.add("listener")
		val task = TaskHandle {
			FakeMatrixSdkState.activeTimelineListeners.remove(listener)
		}
		FakeMatrixSdkState.activeTimelineListeners.add(listener)
		if (!FakeMatrixSdkState.listenerRegistrationSuspendAsynchronously) {
			deliverTimelineUpdate(listener)
			return task
		}
		return suspendCoroutine { continuation ->
			Thread {
				if (FakeMatrixSdkState.listenerRegistrationDelayMs > 0) {
					Thread.sleep(FakeMatrixSdkState.listenerRegistrationDelayMs)
				}
				continuation.resume(task)
				deliverTimelineUpdate(listener)
			}.start()
		}
	}

	suspend fun paginateBackwards(numEvents: UShort): Boolean {
		FakeMatrixSdkState.paginationCallCount++
		FakeMatrixSdkState.paginationRequestedEventCounts.add(numEvents.toInt())
		FakeMatrixSdkState.timelineOperationOrder.add("paginateBackwards")
		FakeMatrixSdkState.paginationFailureToThrow?.let { throw it }
		return FakeMatrixSdkState.paginationHitTimelineStart
	}

	/** Mirrors the pinned AAR's real shape: synchronous, builds an owned closeable value. */
	fun createMessageContent(messageType: MessageType): RoomMessageEventContentWithoutRelation {
		FakeMatrixSdkState.createMessageContentCallCount++
		FakeMatrixSdkState.lastCreatedMessageType = messageType
		return RoomMessageEventContentWithoutRelation(messageType)
	}

	suspend fun send(content: RoomMessageEventContentWithoutRelation): SendHandle {
		FakeMatrixSdkState.sendCallCount++
		FakeMatrixSdkState.lastSentContent = content
		FakeMatrixSdkState.sendFailureToThrow?.let { throw it }
		val handle = SendHandle()
		if (!FakeMatrixSdkState.sendSuspendAsynchronously) return handle
		return suspendCoroutine { continuation ->
			Thread {
				if (FakeMatrixSdkState.sendDelayMs > 0) Thread.sleep(FakeMatrixSdkState.sendDelayMs)
				continuation.resume(handle)
			}.start()
		}
	}

	/** Mirrors the pinned AAR's suspend lookup (MX-058); unknown ids throw like the SDK. */
	suspend fun getEventTimelineItemByEventId(eventId: String): EventTimelineItem =
		FakeMatrixSdkState.eventItemsById[eventId]?.toEventTimelineItem()
			?: throw ClientException.Generic("not found", "not found")

	suspend fun edit(eventOrTransactionId: EventOrTransactionId, newContent: EditedContent) {
		FakeMatrixSdkState.editCallCount++
		FakeMatrixSdkState.lastEditedEventId =
			(eventOrTransactionId as EventOrTransactionId.EventId).eventId
		FakeMatrixSdkState.lastEditedContent = newContent
		FakeMatrixSdkState.editFailureToThrow?.let { throw it }
	}

	fun sendFile(params: UploadParameters, info: FileInfo): SendAttachmentJoinHandle {
		FakeMatrixSdkState.sendFileCallCount++
		FakeMatrixSdkState.lastSendFileParams = params
		FakeMatrixSdkState.lastSendFileInfo = info
		return SendAttachmentJoinHandle()
	}

	/** Mirrors the pinned AAR's real shape: synchronous, returns an owned join handle (MX-014). */
	fun sendImage(
		params: UploadParameters,
		thumbnailSource: UploadSource?,
		imageInfo: ImageInfo,
	): SendAttachmentJoinHandle {
		FakeMatrixSdkState.sendImageCallCount++
		FakeMatrixSdkState.lastSendImageParams = params
		FakeMatrixSdkState.lastSendImageThumbnailSource = thumbnailSource
		FakeMatrixSdkState.lastSendImageInfo = imageInfo
		return SendAttachmentJoinHandle()
	}

	fun close() {
		FakeMatrixSdkState.timelineCloseCount++
		FakeMatrixSdkState.activeTimelineListeners.clear()
	}

	private fun deliverTimelineUpdate(listener: TimelineListener) {
		val delayMs = FakeMatrixSdkState.timelineUpdateDelayMs
		val diffs = if (FakeMatrixSdkState.listenerAddCount > 1) {
			FakeMatrixSdkState.timelineDiffsAfterPagination
				?: FakeMatrixSdkState.timelineDiffsToReturn
		} else {
			FakeMatrixSdkState.timelineDiffsToReturn
		}
		if (delayMs <= 0) {
			listener.onUpdate(diffs)
			return
		}
		Thread {
			Thread.sleep(delayMs)
			listener.onUpdate(diffs)
		}.start()
	}
}

class TaskHandle(private val onCancel: (() -> Unit)? = null) {
	fun cancel() {
		FakeMatrixSdkState.taskCancelCount++
		FakeMatrixSdkState.onTaskCancel?.invoke()
		onCancel?.invoke()
	}

	fun close() {
		FakeMatrixSdkState.taskCloseCount++
		onCancel?.invoke()
	}
}

class TimelineItem(private val event: FakeTimelineEventSpec?) {
	fun asEvent(): EventTimelineItem? = event?.toEventTimelineItem()

	fun close() {
		FakeMatrixSdkState.timelineItemCloseCount++
	}
}

data class FakeTimelineEventSpec(
	val identifier: EventOrTransactionId,
	val sender: String,
	val body: String?,
	val timestamp: ULong,
	val own: Boolean = false,
	val messageType: MessageType = MessageType.Text(),
	val edited: Boolean = false,
	val isReply: Boolean = false,
	val reactions: List<Reaction> = emptyList(),
	val redacted: Boolean = false,
	val editable: Boolean = own,
) {
	fun toEventTimelineItem(): EventTimelineItem {
		val content = if (redacted) {
			TimelineItemContent.MsgLike(MsgLikeContent(MsgLikeKind.Redacted))
		} else if (body == null) {
			TimelineItemContent.Other
		} else {
			TimelineItemContent.MsgLike(
				MsgLikeContent(
					MsgLikeKind.Message(MessageContent(messageType, body, edited)),
					inReplyTo = if (isReply) InReplyToDetails() else null,
					reactions = reactions,
				),
			)
		}
		return EventTimelineItem(identifier, sender, own, content, timestamp, editable)
	}
}

sealed class EventOrTransactionId {
	class EventId(val eventId: String) : EventOrTransactionId()
	class TransactionId(val transactionId: String) : EventOrTransactionId()
}

class EventTimelineItem(
	val eventOrTransactionId: EventOrTransactionId,
	val sender: String,
	private val own: Boolean,
	val content: TimelineItemContent,
	val timestamp: ULong,
	private val editable: Boolean = own,
) {
	fun isOwn(): Boolean = own
	fun isEditable(): Boolean = editable

	fun destroy() {
		FakeMatrixSdkState.eventTimelineItemDestroyCount++
		content.destroy()
	}
}

sealed class TimelineItemContent {
	abstract fun destroy()

	class MsgLike(val content: MsgLikeContent) : TimelineItemContent() {
		override fun destroy() = content.destroy()
	}

	object Other : TimelineItemContent() {
		override fun destroy() {
			FakeMatrixSdkState.otherContentDestroyCount++
		}
	}
}

class MsgLikeContent(
	val kind: MsgLikeKind,
	private val inReplyTo: InReplyToDetails? = null,
	val reactions: List<Reaction> = emptyList(),
) {
	fun getInReplyTo(): InReplyToDetails? = inReplyTo

	fun destroy() {
		kind.destroy()
		inReplyTo?.destroy()
	}
}

data class Reaction(val key: String, val senders: List<ReactionSenderData>)

class ReactionSenderData

class InReplyToDetails {
	fun destroy() {
		FakeMatrixSdkState.inReplyToDetailsDestroyCount++
	}
}

sealed class MsgLikeKind {
	abstract fun destroy()

	object Redacted : MsgLikeKind() {
		override fun destroy() = Unit
	}

	class Message(val content: MessageContent) : MsgLikeKind() {
		override fun destroy() = content.destroy()
	}
}

sealed class MessageType {
	/**
	 * Mirrors the pinned AAR's real shape (MX-013): a class carrying a [TextMessageContent], not
	 * a parameterless singleton, since `Timeline.createMessageContent` requires building one from
	 * plain text to send. The default keeps every pre-existing `MessageType.Text` incoming-message
	 * fixture usage compiling unchanged.
	 */
	class Text(val content: TextMessageContent = TextMessageContent("", null)) : MessageType()
	open class Image(val content: ImageMessageContent? = null) : MessageType() {
		companion object : Image(null)
	}
	object Video : MessageType()
	object Audio : MessageType()
	open class File(val content: FileMessageContent? = null) : MessageType() {
		companion object : File(null)
	}
	object Gallery : MessageType()
	object Location : MessageType()
	object Notice : MessageType()
	object Emote : MessageType()
	object Other : MessageType()
}

class FileMessageContent(
	val filename: String,
	val caption: String? = null,
	val formattedCaption: FormattedBody? = null,
	val source: MediaSource? = null,
	val info: FileInfo? = null,
) {
	fun destroy() {
		FakeMatrixSdkState.messageContentDestroyCount++
	}
}

class ImageMessageContent(
	val filename: String,
	val caption: String? = null,
	val formattedCaption: FormattedBody? = null,
	val source: MediaSource? = null,
	val info: ImageInfo? = null,
) {
	fun destroy() {
		FakeMatrixSdkState.messageContentDestroyCount++
	}
}

/** Mirrors the pinned AAR's real shape: `TextMessageContent(String body, FormattedBody formatted)`. */
class TextMessageContent(val body: String, val formatted: FormattedBody?)

/** Mirrors the pinned AAR's real shape: `FormattedBody(MessageFormat format, String body)`. Not
 * exercised by any current send path, which always passes a null `formatted` body. */
class FormattedBody(val format: Any? = null, val body: String = "")

class MessageContent(val msgType: MessageType, val body: String, val isEdited: Boolean) {
	fun destroy() {
		FakeMatrixSdkState.messageContentDestroyCount++
	}
}

/** Mirrors the pinned AAR's real shape: an owned closeable value `Timeline.send` consumes. */
class RoomMessageEventContentWithoutRelation(val messageType: MessageType) {
	fun close() {
		FakeMatrixSdkState.roomMessageEventContentCloseCount++
	}
}

/** Mirrors the pinned AAR's real shape: `EditedContent.RoomMessage(content)` (MX-058). */
sealed class EditedContent {
	class RoomMessage(val content: RoomMessageEventContentWithoutRelation) : EditedContent()
}

/** Mirrors the pinned AAR's real shape: an owned closeable value `Timeline.send` returns. */
class SendHandle {
	fun close() {
		FakeMatrixSdkState.sendHandleCloseCount++
	}
}

/**
 * Mirrors the pinned AAR's real shape (MX-014): `UploadSource.Data(bytes, filename)` is the only
 * variant any current send path constructs.
 */
sealed class UploadSource {
	class Data(val bytes: ByteArray, val filename: String) : UploadSource()
}

/**
 * Mirrors the pinned AAR's real shape (MX-014): `UploadParameters(source, caption,
 * formattedCaption, mentions, inReplyTo, extraContentJson)`; only [source] is non-nullable.
 * [caption], [mentions], [inReplyTo], and [extraContentJson] are not exercised by any current send
 * path, which always passes null for them.
 */
class UploadParameters(
	val source: UploadSource,
	val caption: String?,
	val formattedCaption: FormattedBody?,
	val mentions: Mentions?,
	val inReplyTo: String?,
	val extraContentJson: String?,
)

/** Unconstructed stand-in for the pinned AAR's `Mentions`; no current send path sends mentions. */
class Mentions

/** Unconstructed stand-in for the pinned AAR's `ThumbnailInfo`; MX-014 sends no thumbnail. */
class ThumbnailInfo

/** Stand-in for the pinned AAR's `MediaSource`; supports url and json constructor/converters (MX-053). */
class MediaSource(val url: String = "", val json: String = "") {
	fun url(): String = url
	fun toJson(): String = json.ifEmpty { url }
	fun destroy() {
		FakeMatrixSdkState.mediaSourceDestroyCount++
	}

	companion object {
		@JvmStatic
		fun fromJson(json: String): MediaSource = MediaSource(url = json, json = json)

		@JvmStatic
		fun fromUrl(url: String): MediaSource = MediaSource(url = url, json = url)
	}
}

class MediaFileHandle(private val tempDir: java.io.File?, private val path: String) :
	AutoCloseable {
	constructor(path: String) : this(null, path)

	fun path(): String = path
	fun persist(newPath: String): Boolean {
		val dest = java.io.File(newPath)
		val src = java.io.File(path)
		if (!src.exists()) return false
		dest.parentFile?.mkdirs()
		return try {
			src.copyTo(dest, overwrite = true)
			src.delete()
			true
		} catch (e: Exception) {
			false
		}
	}
	fun destroy() {
		FakeMatrixSdkState.mediaFileHandleDestroyCount++
		tempDir?.deleteRecursively()
	}
	override fun close() {
		destroy()
	}
}

/** FileInfo has the same synthetic ULong constructor marker as ImageInfo in the pinned AAR. */
class FileInfo(
	val mimetype: String?,
	val size: ULong?,
	val thumbnailInfo: ThumbnailInfo?,
	val thumbnailSource: MediaSource?,
) {
	fun destroy() {
		FakeMatrixSdkState.fileInfoDestroyCount++
	}
}

/**
 * Mirrors the pinned AAR's real shape (MX-014): `ImageInfo`'s public constructor takes an unused
 * `DefaultConstructorMarker` parameter after every real field, verified over javap against the
 * pinned AAR -- not a normal Kotlin default-argument bitmask constructor, so the reflective
 * production caller passes `null` positionally for it, the same as every other nullable field.
 */
class ImageInfo(
	val height: ULong?,
	val width: ULong?,
	val mimetype: String?,
	val size: ULong?,
	val thumbnailInfo: ThumbnailInfo?,
	val thumbnailSource: MediaSource?,
	val blurhash: String?,
	val isAnimated: Boolean?,
) {
	fun destroy() {
		FakeMatrixSdkState.imageInfoDestroyCount++
	}
}

/**
 * Mirrors the pinned AAR's real shape (MX-014): `RoomException` is an abstract sealed base with no
 * public constructor; `Generic` is the concrete variant this fake exercises.
 */
sealed class RoomException(message: String) : Exception(message) {
	class Generic(val msg: String) : RoomException(msg)
}

/**
 * Mirrors the pinned AAR's real shape (MX-014): an owned closeable value `Timeline.sendImage`
 * returns, whose suspend `join()` throws [RoomException] on failure instead of returning one.
 */
class SendAttachmentJoinHandle {
	fun cancel() {
		FakeMatrixSdkState.imageCancelCount++
	}

	suspend fun join() {
		FakeMatrixSdkState.joinCallCount++
		FakeMatrixSdkState.joinFailureToThrow?.let { throw it }
		if (!FakeMatrixSdkState.joinSuspendAsynchronously) {
			FakeMatrixSdkState.onImageJoined?.invoke()
			return
		}
		return suspendCoroutine { continuation ->
			Thread {
				if (FakeMatrixSdkState.joinDelayMs > 0) Thread.sleep(FakeMatrixSdkState.joinDelayMs)
				continuation.resume(Unit)
				FakeMatrixSdkState.joinFinished.countDown()
			}.start()
		}
	}

	fun close() {
		FakeMatrixSdkState.sendAttachmentJoinHandleCloseCount++
	}
}

sealed class TimelineDiff {
	protected abstract val ownedItems: List<TimelineItem>

	fun destroy() {
		FakeMatrixSdkState.timelineDiffDestroyCount++
		ownedItems.forEach(TimelineItem::close)
	}

	class Append(val values: List<TimelineItem>) : TimelineDiff() {
		override val ownedItems = values
	}

	object Clear : TimelineDiff() {
		override val ownedItems = emptyList<TimelineItem>()
	}

	class Insert(val index: UInt, val value: TimelineItem) : TimelineDiff() {
		override val ownedItems = listOf(value)
	}

	object PopBack : TimelineDiff() {
		override val ownedItems = emptyList<TimelineItem>()
	}

	object PopFront : TimelineDiff() {
		override val ownedItems = emptyList<TimelineItem>()
	}

	class PushBack(val value: TimelineItem) : TimelineDiff() {
		override val ownedItems = listOf(value)
	}

	class PushFront(val value: TimelineItem) : TimelineDiff() {
		override val ownedItems = listOf(value)
	}

	class Remove(val index: UInt) : TimelineDiff() {
		override val ownedItems = emptyList<TimelineItem>()
	}

	class Reset(val values: List<TimelineItem>) : TimelineDiff() {
		override val ownedItems = values
	}

	class Set(val index: UInt, val value: TimelineItem) : TimelineDiff() {
		override val ownedItems = listOf(value)
	}

	class Truncate(val length: UInt) : TimelineDiff() {
		override val ownedItems = emptyList<TimelineItem>()
	}
}

class Session(
	accessToken: String,
	val refreshToken: String?,
	val userId: String,
	val deviceId: String,
	val homeserverUrl: String,
	val oauthData: String?,
	val slidingSyncVersion: SlidingSyncVersion,
) {
	init {
		FakeMatrixSdkState.onSessionConstruct?.invoke()
	}
	private val token = accessToken
	val accessToken: String get() {
		FakeMatrixSdkState.onSessionRead?.invoke()
		return token
	}
}

/**
 * Mirrors the pinned SDK's `ClientSessionDelegate`: the app-supplied keychain bridge the SDK
 * calls back into whenever it saves or looks up a `Session`, in particular after an internal
 * token refresh. `retrieveSessionFromKeychain` returns a `@NotNull Session` with no declared
 * `throws` clause in the pinned AAR's bytecode. Harbor's delegate implements this interface
 * directly so a thrown [ClientException] reaches the SDK without a dynamic-proxy wrapper.
 */
interface ClientSessionDelegate {
	fun retrieveSessionFromKeychain(userId: String): Session
	fun saveSessionInKeychain(session: Session)
}

class Client internal constructor(
	private val homeserverUrl: String?,
	private val sessionDelegate: ClientSessionDelegate?,
) {
	private var session: Session? = null

	fun homeserver(): String? = homeserverUrl

	/** Throws like the real SDK until a session exists from [login] or [restoreSession]. */
	fun session(): Session = session ?: throw ClientException.Generic("no session", "no session")

	/** Mirrors the pinned AAR's real shape: synchronous, returns a fresh `Room` handle per call. */
	@Suppress("UNCHECKED_CAST")
	fun rooms(): List<Room> = FakeMatrixSdkState.roomsToReturn.map { fake ->
		if (fake.malformedRoomId) {
			MalformedRoom(fake.roomId, fake.displayName, fake.isSpace, fake.membership)
		} else {
			Room(
				fake.roomId,
				fake.displayName,
				fake.isSpace,
				fake.membership,
				fake.unreadMessagesCount,
				fake.topic,
			)
		}
	} as List<Room>

	fun getRoom(roomId: String): Room? = FakeMatrixSdkState.roomsToReturn
		.firstOrNull { it.roomId == roomId }
		?.let { fake ->
			Room(
				fake.roomId,
				fake.displayName,
				fake.isSpace,
				fake.membership,
				fake.unreadMessagesCount,
				fake.topic,
			)
		}

	suspend fun login(
		username: String,
		password: String,
		initialDeviceName: String?,
		deviceId: String?,
	) {
		FakeMatrixSdkState.loginCallCount++
		session = Session(
			accessToken = "fake-access-token",
			refreshToken = null,
			userId = "@$username:matrix.example.org",
			deviceId = "FAKEDEVICE",
			homeserverUrl = homeserverUrl.orEmpty(),
			oauthData = null,
			slidingSyncVersion = SlidingSyncVersion.NATIVE,
		)
		sessionDelegate?.saveSessionInKeychain(session!!)
		FakeMatrixSdkState.onLogin?.invoke()
	}

	suspend fun restoreSession(session: Session) {
		FakeMatrixSdkState.restoreSessionCallCount++
		FakeMatrixSdkState.restoreSessionException?.let { throw it }
		this.session = session
	}

	fun userId(): String = session?.userId ?: throw ClientException.Generic("no session", "no session")

	suspend fun logout() {
		FakeMatrixSdkState.onDispose?.invoke()
		FakeMatrixSdkState.logoutCallCount++
		session = null
	}

	fun close() {
		FakeMatrixSdkState.onDispose?.invoke()
		FakeMatrixSdkState.clientCloseCount++
	}

	suspend fun getMediaThumbnail(source: MediaSource, width: ULong, height: ULong): ByteArray {
		FakeMatrixSdkState.getMediaThumbnailCallCount++
		FakeMatrixSdkState.lastMediaThumbnailSource = source
		FakeMatrixSdkState.lastMediaThumbnailWidth = width
		FakeMatrixSdkState.lastMediaThumbnailHeight = height
		FakeMatrixSdkState.onGetMediaThumbnail?.invoke()
		FakeMatrixSdkState.mediaThumbnailFailureToThrow?.let { throw it }
		return FakeMatrixSdkState.mediaThumbnailToReturn ?: ByteArray(0)
	}

	suspend fun getMediaFile(
		mediaSource: MediaSource,
		filename: String?,
		mimeType: String,
		useCache: Boolean,
		tempDir: String?,
	): MediaFileHandle {
		FakeMatrixSdkState.getMediaFileCallCount++
		coroutineContext[Job]?.invokeOnCompletion { cause ->
			if (cause is CancellationException) {
				FakeMatrixSdkState.downloadJobCancelled = true
			}
		}
		FakeMatrixSdkState.onGetMediaFile?.invoke()
		FakeMatrixSdkState.getMediaFileException?.let { throw it }
		val dirPath = tempDir ?: System.getProperty("java.io.tmpdir") ?: "."
		val dir = java.io.File(dirPath)
		dir.mkdirs()
		val handleDir = java.io.File(dir, "tempdir_" + java.util.UUID.randomUUID())
		handleDir.mkdirs()
		val targetName = filename ?: "downloaded_file"
		val file = java.io.File(handleDir, targetName)
		val bytes = FakeMatrixSdkState.mediaContentToReturn ?: "fake file content".toByteArray()
		file.writeBytes(bytes)
		return MediaFileHandle(handleDir, file.absolutePath)
	}

	/**
	 * Test-only stand-in for the SDK internally refreshing an expired access token: mirrors what
	 * a real token refresh does by mutating [session] and driving [sessionDelegate]'s save
	 * callback, so a production fix that never installs the delegate leaves this update
	 * unpersisted.
	 */
	fun simulateTokenRefresh(newAccessToken: String) {
		val current = session ?: return
		val refreshed = Session(
			accessToken = newAccessToken,
			refreshToken = current.refreshToken,
			userId = current.userId,
			deviceId = current.deviceId,
			homeserverUrl = current.homeserverUrl,
			oauthData = current.oauthData,
			slidingSyncVersion = current.slidingSyncVersion,
		)
		session = refreshed
		FakeMatrixSdkState.tokenRefreshCallCount++
		sessionDelegate?.saveSessionInKeychain(refreshed)
	}
}

/**
 * Mirrors the pinned SDK's real shape: `ClientBuilder` is `AutoCloseable`, and
 * [serverName], [inMemoryStore], and [sqliteStore] each return a distinct
 * closeable wrapper rather than mutating the receiver, so a fix that only closes
 * one instance still leaks the others.
 */
class ClientBuilder : AutoCloseable {

	fun serverName(serverName: String): ClientBuilder {
		FakeMatrixSdkState.lastServerName = serverName
		return ClientBuilder()
	}

	fun inMemoryStore(): ClientBuilder {
		FakeMatrixSdkState.inMemoryStoreCalled = true
		FakeMatrixSdkState.sqliteStoreCalled = false
		return ClientBuilder()
	}

	fun homeserverUrl(homeserverUrl: String): ClientBuilder {
		FakeMatrixSdkState.lastHomeserverUrl = homeserverUrl
		return ClientBuilder()
	}

	fun sqliteStore(storeBuilder: SqliteStoreBuilder): ClientBuilder {
		FakeMatrixSdkState.sqliteStoreCalled = true
		FakeMatrixSdkState.inMemoryStoreCalled = false
		return ClientBuilder()
	}

	fun setSessionDelegate(delegate: ClientSessionDelegate): ClientBuilder {
		FakeMatrixSdkState.lastSessionDelegate = delegate
		return ClientBuilder()
	}

	override fun close() {
		FakeMatrixSdkState.builderCloseCount++
	}

	suspend fun build(): Client {
		FakeMatrixSdkState.buildCallCount++
		FakeMatrixSdkState.failureToThrow?.let { failure ->
			if (!FakeMatrixSdkState.failAsynchronously) throw failure
		}
		if (!FakeMatrixSdkState.suspendAsynchronously) {
			return newClient()
		}
		return suspendCoroutine { continuation ->
			Thread {
				if (FakeMatrixSdkState.asyncDelayMs > 0) Thread.sleep(FakeMatrixSdkState.asyncDelayMs)
				val asyncFailure = FakeMatrixSdkState.failureToThrow
				if (asyncFailure != null && FakeMatrixSdkState.failAsynchronously) {
					continuation.resumeWithException(asyncFailure)
				} else {
					continuation.resume(newClient())
				}
			}.also {
				FakeMatrixSdkState.builds.add(it)
				it.start()
			}
		}
	}

	private fun newClient(): Client = Client(
		FakeMatrixSdkState.lastHomeserverUrl ?: FakeMatrixSdkState.homeserverUrlToReturn,
		FakeMatrixSdkState.lastSessionDelegate,
	).also {
		FakeMatrixSdkState.lastBuiltClient = it
	}
}

/** Mirrors the pinned SDK's `SqliteStoreBuilder`: also `AutoCloseable`, also wrapper-per-call. */
class SqliteStoreBuilder(dataPath: String, cachePath: String) : AutoCloseable {
	init {
		FakeMatrixSdkState.lastSqliteDataPath = dataPath
		FakeMatrixSdkState.lastSqliteCachePath = cachePath
	}

	fun passphrase(passphrase: String): SqliteStoreBuilder {
		FakeMatrixSdkState.lastPassphrase = passphrase
		return SqliteStoreBuilder(
			FakeMatrixSdkState.lastSqliteDataPath.orEmpty(),
			FakeMatrixSdkState.lastSqliteCachePath.orEmpty(),
		)
	}

	override fun close() {
		FakeMatrixSdkState.storeBuilderCloseCount++
	}
}

/** Spec for a fake `Room` [Client.rooms] returns; kept separate from [Room] so tests set up input plainly. */
data class FakeRoomSpec(
	val roomId: String,
	val displayName: String? = null,
	val isSpace: Boolean = false,
	val membership: Membership = Membership.JOINED,
	val malformedRoomId: Boolean = false,
	val unreadMessagesCount: ULong? = null,
	val topic: String? = null,
)

/** Test-only malformed runtime handle returned through `Client.rooms()`'s erased list type. */
class MalformedRoom(
	private val closeTrackingId: String,
	private val roomDisplayName: String?,
	private val space: Boolean,
	private val roomMembership: Membership,
) {
	fun id(): Int = 1
	fun displayName(): String? = roomDisplayName
	fun isSpace(): Boolean = space
	fun membership(): Membership = roomMembership
	fun close() {
		FakeMatrixSdkState.roomCloseCount++
		FakeMatrixSdkState.closedRoomHandles.add(closeTrackingId)
	}
}

object FakeMatrixSdkState {
	val builds = mutableListOf<Thread>()
	fun awaitBuilds() {
		builds.forEach {
			it.join(3000)
			check(!it.isAlive)
		}
		builds.clear()
	}
	var lastServerName: String? = null
	var inMemoryStoreCalled = false
	var sqliteStoreCalled = false
	var homeserverUrlToReturn: String? = "https://matrix.example.org"
	var failureToThrow: ClientBuildException? = null
	var failAsynchronously = false
	var suspendAsynchronously = false
	var asyncDelayMs = 0L
	var buildCallCount = 0
	var clientCloseCount = 0
	var builderCloseCount = 0
	var storeBuilderCloseCount = 0
	var lastHomeserverUrl: String? = null
	var lastSqliteDataPath: String? = null
	var lastSqliteCachePath: String? = null
	var lastPassphrase: String? = null
	var loginCallCount = 0
	var restoreSessionCallCount = 0
	var restoreSessionException: Exception? = null
	var logoutCallCount = 0
	var tokenRefreshCallCount = 0
	var lastSessionDelegate: ClientSessionDelegate? = null
	var onSessionConstruct: (() -> Unit)? = null
	var onSessionRead: (() -> Unit)? = null
	var onLogin: (() -> Unit)? = null
	var onDispose: (() -> Unit)? = null
	var lastBuiltClient: Client? = null
	var roomsToReturn: List<FakeRoomSpec> = emptyList()
	var roomCloseCount = 0
	var leaveCalls = 0
	var leaveFails = false
	val closedRoomHandles = mutableListOf<String>()
	var timelineCallCount = 0
	var timelineSuspendAsynchronously = false
	var timelineDelayMs = 0L
	var listenerAddCount = 0
	var listenerRegistrationSuspendAsynchronously = false
	var listenerRegistrationDelayMs = 0L
	var timelineUpdateDelayMs = 0L
	var timelineDiffsToReturn: List<TimelineDiff> = emptyList()
	var timelineDiffsAfterPagination: List<TimelineDiff>? = null
	var paginationCallCount = 0
	val paginationRequestedEventCounts = mutableListOf<Int>()
	val timelineOperationOrder = mutableListOf<String>()
	var paginationHitTimelineStart = false
	var paginationFailureToThrow: Exception? = null
	var timelineCloseCount = 0
	val activeTimelineListeners = mutableListOf<TimelineListener>()
	val activeTypingListeners = mutableListOf<TypingNotificationsListener>()
	var typingListenerAddCount = 0

	fun simulateTypingNotification(typingUserIds: List<String>) {
		val listeners = synchronized(activeTypingListeners) { activeTypingListeners.toList() }
		listeners.forEach { it.call(typingUserIds) }
	}

	/** Runs inside [TaskHandle.cancel], e.g. to emulate a native cancel awaiting callbacks. */
	var onTaskCancel: (() -> Unit)? = null

	fun simulateRemoteEvent(diffs: List<TimelineDiff>) {
		val listeners = synchronized(activeTimelineListeners) { activeTimelineListeners.toList() }
		listeners.forEach { it.onUpdate(diffs) }
	}

	var taskCancelCount = 0
	var taskCloseCount = 0
	var timelineDiffDestroyCount = 0
	var timelineItemCloseCount = 0
	var eventTimelineItemDestroyCount = 0
	var messageContentDestroyCount = 0
	var otherContentDestroyCount = 0
	var inReplyToDetailsDestroyCount = 0
	var createMessageContentCallCount = 0
	var lastCreatedMessageType: MessageType? = null
	var sendCallCount = 0
	var lastSentContent: RoomMessageEventContentWithoutRelation? = null
	var sendFailureToThrow: ClientException? = null
	var eventItemsById: Map<String, FakeTimelineEventSpec> = emptyMap()
	var editCallCount = 0
	var lastEditedEventId: String? = null
	var lastEditedContent: EditedContent? = null
	var editFailureToThrow: ClientException? = null
	var sendSuspendAsynchronously = false
	var sendDelayMs = 0L
	var roomMessageEventContentCloseCount = 0
	var sendHandleCloseCount = 0
	var encryptionState = EncryptionState.NOT_ENCRYPTED
	var onImageJoined: (() -> Unit)? = null
	var joinFinished = java.util.concurrent.CountDownLatch(1)
	var imageCancelCount = 0
	var sendFileCallCount = 0
	var lastSendFileParams: UploadParameters? = null
	var lastSendFileInfo: FileInfo? = null
	var fileInfoDestroyCount = 0
	var sendImageCallCount = 0
	var lastSendImageParams: UploadParameters? = null
	var lastSendImageThumbnailSource: UploadSource? = null
	var lastSendImageInfo: ImageInfo? = null
	var imageInfoDestroyCount = 0
	var joinCallCount = 0
	var joinFailureToThrow: RoomException? = null
	var joinSuspendAsynchronously = false
	var joinDelayMs = 0L
	var sendAttachmentJoinHandleCloseCount = 0
	var markAsReadCallCount = 0
	val typingCalls = mutableListOf<Boolean>()
	var typingOperation: suspend (Boolean) -> Unit = {}
	val markedUnreadRooms = mutableMapOf<String, Boolean>()
	var lastMarkAsReadRoomId: String? = null
	var lastMarkAsReadReceiptType: ReceiptType? = null
	var markAsReadFailureToThrow: ClientException? = null
	var roomInfoCallCount = 0
	var roomInfoDestroyCount = 0
	var roomInfoFailureToThrow: Exception? = null
	var getMediaFileCallCount = 0
	var getMediaFileException: Exception? = null
	var onGetMediaFile: (suspend () -> Unit)? = null
	var mediaContentToReturn: ByteArray? = null
	var mediaFileHandleDestroyCount = 0
	var downloadJobCancelled = false
	var getMediaThumbnailCallCount = 0
	var mediaSourceDestroyCount = 0
	var mediaThumbnailFailureToThrow: Throwable? = null
	var mediaThumbnailToReturn: ByteArray? = null
	var lastMediaThumbnailSource: MediaSource? = null
	var lastMediaThumbnailWidth: ULong? = null
	var lastMediaThumbnailHeight: ULong? = null
	var onGetMediaThumbnail: (() -> Unit)? = null

	fun reset() {
		awaitBuilds()
		getMediaThumbnailCallCount = 0
		mediaSourceDestroyCount = 0
		mediaThumbnailFailureToThrow = null
		mediaThumbnailToReturn = null
		lastMediaThumbnailSource = null
		lastMediaThumbnailWidth = null
		lastMediaThumbnailHeight = null
		onGetMediaThumbnail = null
		getMediaFileCallCount = 0
		getMediaFileException = null
		onGetMediaFile = null
		mediaContentToReturn = null
		mediaFileHandleDestroyCount = 0
		downloadJobCancelled = false
		roomInfoCallCount = 0
		roomInfoDestroyCount = 0
		roomInfoFailureToThrow = null
		onSessionConstruct = null
		onSessionRead = null
		onLogin = null
		onDispose = null
		lastServerName = null
		inMemoryStoreCalled = false
		sqliteStoreCalled = false
		homeserverUrlToReturn = "https://matrix.example.org"
		failureToThrow = null
		failAsynchronously = false
		suspendAsynchronously = false
		asyncDelayMs = 0L
		buildCallCount = 0
		clientCloseCount = 0
		builderCloseCount = 0
		storeBuilderCloseCount = 0
		lastHomeserverUrl = null
		lastSqliteDataPath = null
		lastSqliteCachePath = null
		lastPassphrase = null
		loginCallCount = 0
		restoreSessionCallCount = 0
		restoreSessionException = null
		logoutCallCount = 0
		tokenRefreshCallCount = 0
		lastSessionDelegate = null
		lastBuiltClient = null
		roomsToReturn = emptyList()
		roomCloseCount = 0
		leaveCalls = 0
		leaveFails = false
		closedRoomHandles.clear()
		timelineCallCount = 0
		timelineSuspendAsynchronously = false
		timelineDelayMs = 0L
		listenerAddCount = 0
		listenerRegistrationSuspendAsynchronously = false
		listenerRegistrationDelayMs = 0L
		timelineUpdateDelayMs = 0L
		timelineDiffsToReturn = emptyList()
		timelineDiffsAfterPagination = null
		paginationCallCount = 0
		paginationRequestedEventCounts.clear()
		timelineOperationOrder.clear()
		paginationHitTimelineStart = false
		paginationFailureToThrow = null
		timelineCloseCount = 0
		activeTimelineListeners.clear()
		activeTypingListeners.clear()
		typingListenerAddCount = 0
		onTaskCancel = null
		taskCancelCount = 0
		taskCloseCount = 0
		timelineDiffDestroyCount = 0
		timelineItemCloseCount = 0
		eventTimelineItemDestroyCount = 0
		messageContentDestroyCount = 0
		otherContentDestroyCount = 0
		inReplyToDetailsDestroyCount = 0
		createMessageContentCallCount = 0
		lastCreatedMessageType = null
		sendCallCount = 0
		lastSentContent = null
		sendFailureToThrow = null
		eventItemsById = emptyMap()
		editCallCount = 0
		lastEditedEventId = null
		lastEditedContent = null
		editFailureToThrow = null
		sendSuspendAsynchronously = false
		sendDelayMs = 0L
		roomMessageEventContentCloseCount = 0
		sendHandleCloseCount = 0
		encryptionState = EncryptionState.NOT_ENCRYPTED
		onImageJoined = null
		joinFinished = java.util.concurrent.CountDownLatch(1)
		imageCancelCount = 0
		sendFileCallCount = 0
		lastSendFileParams = null
		lastSendFileInfo = null
		fileInfoDestroyCount = 0
		sendImageCallCount = 0
		lastSendImageParams = null
		lastSendImageThumbnailSource = null
		lastSendImageInfo = null
		imageInfoDestroyCount = 0
		joinCallCount = 0
		joinFailureToThrow = null
		joinSuspendAsynchronously = false
		joinDelayMs = 0L
		sendAttachmentJoinHandleCloseCount = 0
		markAsReadCallCount = 0
		typingCalls.clear()
		typingOperation = {}
		markedUnreadRooms.clear()
		lastMarkAsReadRoomId = null
		lastMarkAsReadReceiptType = null
		markAsReadFailureToThrow = null
	}
}
