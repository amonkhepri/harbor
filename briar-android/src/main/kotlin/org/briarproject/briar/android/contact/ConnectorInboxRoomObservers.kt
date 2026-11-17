package org.briarproject.briar.android.contact

import org.briarproject.briar.api.connector.ConnectorRoomObserver
import org.briarproject.briar.api.connector.ConnectorRoomObserver.RoomSubscription
import org.briarproject.briar.api.connector.ConnectorSource
import org.briarproject.briar.api.connector.ConnectorThread
import org.briarproject.briar.api.connector.ReadOnlyConnector

/**
 * Keeps one [ConnectorRoomObserver] subscription per visible joined inbox row so remote
 * activity can invalidate the inbox snapshot (MX-046). Subscriptions are bounded by the
 * rows the inbox already loaded and are fenced by a per-source session marker: when the
 * marker changes (sign-out, re-login, expiry) every subscription of that source is
 * replaced, and callbacks from replaced or cancelled subscriptions are dropped.
 *
 * Native cancellation always runs outside this object's lock because an SDK cancel may
 * wait for an in-flight callback that itself needs the lock.
 */
internal class ConnectorInboxRoomObservers(private val onRemoteActivity: () -> Unit) {
	private class Entry(val fence: Any?, val token: Any, val subscription: RoomSubscription)

	private val entries = HashMap<ConnectorSource, HashMap<String, Entry>>()
	private var closed = false

	@get:Synchronized
	val isClosed: Boolean
		get() = closed

	/** Reconciles subscriptions for [connector] with freshly loaded [threads]. IO thread only. */
	fun update(connector: ReadOnlyConnector, fence: Any?, threads: List<ConnectorThread>) {
		val observer = connector as? ConnectorRoomObserver
		if (observer == null) {
			clear(connector.source)
			return
		}
		val source = connector.source
		val wanted = threads.filterNot { it.isInvited }.mapTo(LinkedHashSet()) { it.threadId }
		val stale = ArrayList<RoomSubscription>()
		val missing = synchronized(this) {
			if (closed) return
			val current = entries.getOrPut(source) { HashMap() }
			val iterator = current.entries.iterator()
			while (iterator.hasNext()) {
				val entry = iterator.next()
				if (entry.key !in wanted || entry.value.fence !== fence) {
					stale.add(entry.value.subscription)
					iterator.remove()
				}
			}
			wanted.filterNot { it in current }
		}
		stale.forEach(::cancelQuietly)
		missing.forEach { threadId -> subscribe(observer, source, threadId, fence) }
	}

	/** Drops every subscription of [source], e.g. after sign-out or session expiry. */
	fun clear(source: ConnectorSource) {
		val removed = synchronized(this) { entries.remove(source)?.values?.toList() }
		removed?.forEach { cancelQuietly(it.subscription) }
	}

	fun close() {
		val removed = synchronized(this) {
			closed = true
			entries.values.flatMap { it.values }.also { entries.clear() }
		}
		removed.forEach { cancelQuietly(it.subscription) }
	}

	private fun subscribe(
		observer: ConnectorRoomObserver,
		source: ConnectorSource,
		threadId: String,
		fence: Any?,
	) {
		val token = Any()
		val subscription = try {
			observer.observeRoom(threadId) { onRoomUpdate(source, threadId, token) }
		} catch (e: RuntimeException) {
			null
		} ?: return
		val kept = synchronized(this) {
			val current = entries[source]
			if (closed || current == null || threadId in current) {
				false
			} else {
				current[threadId] = Entry(fence, token, subscription)
				true
			}
		}
		if (!kept) cancelQuietly(subscription)
	}

	private fun onRoomUpdate(source: ConnectorSource, threadId: String, token: Any) {
		val current = synchronized(this) {
			!closed && entries[source]?.get(threadId)?.token === token
		}
		if (current) onRemoteActivity()
	}

	private fun cancelQuietly(subscription: RoomSubscription) {
		try {
			subscription.cancel()
		} catch (e: RuntimeException) {
			// Best-effort teardown; a failed cancel must not break inbox loading.
		}
	}
}
