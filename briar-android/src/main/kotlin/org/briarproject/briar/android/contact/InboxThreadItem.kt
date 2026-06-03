package org.briarproject.briar.android.contact

interface InboxThreadItem {

	enum class Source {
		BRIAR,
		TELEGRAM
	}

	val stableId: String

	val latestActivityMillis: Long

	val source: Source
}
