package org.briarproject.briar.android.matrix

import android.app.Activity
import android.app.Application
import android.view.LayoutInflater
import android.widget.EditText
import androidx.preference.PreferenceManager
import org.briarproject.briar.BuildConfig
import org.briarproject.briar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MatrixSetupEntryTest {
	@Test fun entryMatchesBuildFlagAndRoutesToSetup() {
		val application = RuntimeEnvironment.getApplication()
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		val screen = PreferenceManager(activity).createPreferenceScreen(activity)
		MatrixSetupEntry.addTo(screen)
		assertEquals(if (BuildConfig.MATRIX_CONNECTOR_ENABLED) 1 else 0, screen.preferenceCount)
		if (BuildConfig.MATRIX_CONNECTOR_ENABLED) {
			val preference = screen.getPreference(0)
			preference.onPreferenceClickListener!!.onPreferenceClick(preference)
			assertEquals(
				MatrixSetupActivity::class.java.name,
				shadowOf(application).nextStartedActivity.component!!.className,
			)
		}
	}

	@Test fun passwordFieldDoesNotSaveOrAutofillItsContents() {
		val application = RuntimeEnvironment.getApplication()
		application.setTheme(R.style.BriarTheme)
		val view = LayoutInflater.from(application).inflate(R.layout.activity_matrix_setup, null)
		val password = view.findViewById<EditText>(R.id.matrixPassword)
		assertFalse(password.isSaveEnabled)
		assertEquals(android.view.View.IMPORTANT_FOR_AUTOFILL_NO, password.importantForAutofill)
	}
}
