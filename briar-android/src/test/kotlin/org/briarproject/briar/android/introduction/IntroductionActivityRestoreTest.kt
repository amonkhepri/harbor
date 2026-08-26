package org.briarproject.briar.android.introduction

import android.app.Application
import android.os.Bundle
import org.briarproject.briar.android.introduction.IntroductionActivity.Companion.restoredSecondContactId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class IntroductionActivityRestoreTest {

	@Test
	fun returnsNullWhenRotatedBeforeSecondContactWasChosen() {
		// onSaveInstanceState() never writes the key while the user is still
		// on ContactChooserFragment, so the restored Bundle is empty here.
		val savedInstanceState = Bundle()

		assertNull(restoredSecondContactId(savedInstanceState))
	}

	@Test
	fun restoresPersistedSecondContactId() {
		val savedInstanceState = Bundle().apply { putInt("contact2", 42) }

		assertEquals(42, restoredSecondContactId(savedInstanceState))
	}
}
