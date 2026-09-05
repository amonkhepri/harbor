package org.briarproject.briar.android.matrix

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.briarproject.bramble.PoliteExecutor
import org.briarproject.bramble.api.lifecycle.IoExecutor
import org.briarproject.briar.BuildConfig
import org.briarproject.briar.R
import org.briarproject.briar.android.activity.ActivityComponent
import org.briarproject.briar.android.activity.BriarActivity
import org.briarproject.briar.api.matrix.MatrixAuthSession
import org.briarproject.briar.api.matrix.MatrixAuthSession.RecoverableErrorDetail
import java.util.concurrent.Executor
import javax.inject.Inject

class MatrixSetupActivity : BriarActivity() {
	@Inject lateinit var matrixAuthSession: MatrixAuthSession

	@Inject @IoExecutor
	lateinit var ioExecutor: Executor

	private lateinit var model: MatrixSetupViewModel
	private lateinit var homeserver: EditText
	private lateinit var username: EditText
	private lateinit var password: EditText
	private lateinit var submit: Button
	private lateinit var status: TextView

	override fun injectActivity(component: ActivityComponent) {
		component.inject(this)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (!BuildConfig.MATRIX_CONNECTOR_ENABLED) {
			finish()
			return
		}
		setContentView(R.layout.activity_matrix_setup)
		homeserver = findViewById(R.id.matrixHomeserver)
		username = findViewById(R.id.matrixUsername)
		password = findViewById(R.id.matrixPassword)
		submit = findViewById(R.id.matrixSubmit)
		status = findViewById(R.id.matrixStatus)
		model = ViewModelProvider(
			this,
			object : ViewModelProvider.Factory {
				@Suppress("UNCHECKED_CAST")
				override fun <T : ViewModel> create(modelClass: Class<T>): T = MatrixSetupViewModel(
					matrixAuthSession,
					PoliteExecutor("MatrixAuth", ioExecutor, 1),
					BuildConfig.MATRIX_CONNECTOR_ENABLED,
				) as T
			},
		)[MatrixSetupViewModel::class.java]
		model.state.observe(this, ::render)
		submit.setOnClickListener {
			val snapshot = model.state.value?.snapshot ?: return@setOnClickListener
			when (setupStep(snapshot)) {
				MatrixSetupStep.HOMESERVER -> model.submitHomeserver(homeserver.text.toString())
				MatrixSetupStep.CREDENTIALS -> {
					val secret = password.text.toString()
					password.text.clear()
					model.submitCredentials(username.text.toString(), secret)
				}
				else -> Unit
			}
		}
		model.start()
	}

	private fun render(state: MatrixSetupState) {
		val step = state.snapshot?.let(::setupStep)
		homeserver.visibility = if (step == MatrixSetupStep.HOMESERVER) View.VISIBLE else View.GONE
		val credentialsVisible = step == MatrixSetupStep.CREDENTIALS
		username.visibility = if (credentialsVisible) View.VISIBLE else View.GONE
		password.visibility = username.visibility
		if (!credentialsVisible) password.text.clear()
		val canSubmit = step == MatrixSetupStep.HOMESERVER || credentialsVisible
		submit.visibility = if (canSubmit) View.VISIBLE else View.GONE
		submit.isEnabled = !state.busy
		homeserver.isEnabled = !state.busy
		username.isEnabled = !state.busy
		password.isEnabled = !state.busy
		submit.setText(if (credentialsVisible) R.string.matrix_sign_in else R.string.matrix_continue)
		val error = state.snapshot?.errorDetail
		val message = when {
			state.busy -> R.string.matrix_working
			step == MatrixSetupStep.READY -> R.string.matrix_ready
			error == RecoverableErrorDetail.UNSUPPORTED_AUTH_STEP -> R.string.matrix_unsupported
			error == RecoverableErrorDetail.DEVICE_KEYSTORE_UNAVAILABLE ||
				error == RecoverableErrorDetail.PERSISTED_SESSION_IDENTITY_UNVERIFIED ->
				R.string.matrix_store_failed
			step == MatrixSetupStep.UNAVAILABLE -> R.string.matrix_unavailable
			state.actionFailed || (error != null && error != RecoverableErrorDetail.NONE) ->
				R.string.matrix_setup_failed
			credentialsVisible -> R.string.matrix_sign_in
			else -> R.string.matrix_homeserver
		}
		status.setText(message)
		if (step == MatrixSetupStep.READY) {
			(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
				.hideSoftInputFromWindow(password.windowToken, 0)
		}
	}

	override fun onStop() {
		if (::password.isInitialized) password.text.clear()
		super.onStop()
	}
}
