package org.briarproject.briar.android.telegram

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertSame
import org.junit.Test

class TelegramConversationActivityLayoutStateTest {

	private class FakeParcelable : Parcelable {
		override fun describeContents(): Int = 0
		override fun writeToParcel(dest: Parcel, flags: Int) {}
	}

	@Test
	fun prefersUnconsumedRestoredStateOverCurrentLayoutManagerState() {
		val pendingRestoreState = FakeParcelable()
		val currentLayoutManagerState = FakeParcelable()

		val result = layoutManagerStateToSave(pendingRestoreState, currentLayoutManagerState)

		assertSame(pendingRestoreState, result)
	}

	@Test
	fun fallsBackToCurrentLayoutManagerStateOncePendingStateIsConsumed() {
		val currentLayoutManagerState = FakeParcelable()

		val result = layoutManagerStateToSave(null, currentLayoutManagerState)

		assertSame(currentLayoutManagerState, result)
	}
}
