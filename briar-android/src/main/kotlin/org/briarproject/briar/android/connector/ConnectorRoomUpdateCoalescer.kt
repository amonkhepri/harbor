package org.briarproject.briar.android.connector

/**
 * Coalesces rapid room update notifications into bounded, serialized refreshes (MX-045).
 */
internal class ConnectorRoomUpdateCoalescer(
	private val refreshAction: () -> Unit,
) {
	private var refreshing = false
	private var pending = false
	private var active = true

	@Synchronized
	fun onUpdate() {
		if (!active) return
		if (refreshing) {
			pending = true
		} else {
			refreshing = true
			refreshAction()
		}
	}

	@Synchronized
	fun onRefreshCompleted() {
		if (!active) return
		if (pending) {
			pending = false
			refreshAction()
		} else {
			refreshing = false
		}
	}

	@Synchronized
	fun cancel() {
		active = false
		refreshing = false
		pending = false
	}
}
