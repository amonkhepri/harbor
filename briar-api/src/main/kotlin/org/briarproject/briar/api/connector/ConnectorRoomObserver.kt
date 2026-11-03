package org.briarproject.briar.api.connector

import java.io.Closeable

/**
 * Opt-in connector capability to observe live updates for an open room/thread (MX-045).
 */
interface ConnectorRoomObserver {
	interface RoomSubscription : Closeable {
		fun cancel()
		override fun close()
	}

	/**
	 * Subscribes to updates for [threadId]. When a remote event occurs for this room,
	 * [onUpdate] is invoked. Returns null if observation is not supported or not authorized.
	 * Closing or cancelling the returned [RoomSubscription] cancels observation and stops callbacks.
	 */
	fun observeRoom(threadId: String, onUpdate: () -> Unit): RoomSubscription?
}
