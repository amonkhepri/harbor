package org.briarproject.briar.android.connector

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for room-qualified text drafts across conversation navigation (MX-049).
 * Preserves unsent text drafts per (sourceId, threadId) without disk persistence.
 */
internal object ConnectorDraftStore {

	private data class Key(val sourceId: String, val threadId: String)

	private val drafts = ConcurrentHashMap<Key, String>()

	fun getDraft(sourceId: String?, threadId: String?): String? {
		if (sourceId.isNullOrEmpty() || threadId.isNullOrEmpty()) return null
		return drafts[Key(sourceId, threadId)]
	}

	fun setDraft(sourceId: String?, threadId: String?, draft: String?) {
		if (sourceId.isNullOrEmpty() || threadId.isNullOrEmpty()) return
		val key = Key(sourceId, threadId)
		if (draft.isNullOrEmpty()) {
			drafts.remove(key)
		} else {
			drafts[key] = draft
		}
	}

	fun clearDraft(sourceId: String?, threadId: String?) {
		if (sourceId.isNullOrEmpty() || threadId.isNullOrEmpty()) return
		drafts.remove(Key(sourceId, threadId))
	}

	fun clearSource(sourceId: String?) {
		if (sourceId.isNullOrEmpty()) return
		val iterator = drafts.entries.iterator()
		while (iterator.hasNext()) {
			if (iterator.next().key.sourceId == sourceId) {
				iterator.remove()
			}
		}
	}

	fun clearAll() {
		drafts.clear()
	}
}
