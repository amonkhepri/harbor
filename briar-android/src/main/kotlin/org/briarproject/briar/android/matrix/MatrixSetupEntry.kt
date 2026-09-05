package org.briarproject.briar.android.matrix

import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.briarproject.briar.BuildConfig
import org.briarproject.briar.R

object MatrixSetupEntry {
	@JvmStatic
	fun addTo(screen: PreferenceScreen) {
		if (!BuildConfig.MATRIX_CONNECTOR_ENABLED) return
		screen.addPreference(
			Preference(screen.context).apply {
				key = "matrix_setup"
				setTitle(R.string.matrix_setup_title)
				setOnPreferenceClickListener {
					context.startActivity(Intent(context, MatrixSetupActivity::class.java))
					true
				}
			},
		)
	}
}
