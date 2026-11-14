package org.briarproject.briar.android.matrix

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
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
import org.briarproject.briar.api.matrix.MatrixDeviceTrustState
import org.briarproject.briar.api.matrix.MatrixVerificationState
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
	private lateinit var account: TextView
	private lateinit var signOut: Button
	private lateinit var roomIdentifier: EditText
	private lateinit var joinRoom: Button
	private lateinit var deviceTrust: TextView
	private lateinit var verifyDevice: Button
	private lateinit var verificationData: TextView
	private lateinit var verificationActions: View
	private lateinit var confirmVerification: Button
	private lateinit var declineVerification: Button
	private lateinit var cancelVerification: Button

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
		account = findViewById(R.id.matrixAccount)
		signOut = findViewById(R.id.matrixSignOut)
		roomIdentifier = findViewById(R.id.matrixRoomIdentifier)
		joinRoom = findViewById(R.id.matrixJoinRoom)
		joinRoom.setOnClickListener { model.joinRoom(roomIdentifier.text.toString()) }
		deviceTrust = findViewById(R.id.matrixDeviceTrust)
		verifyDevice = findViewById(R.id.matrixVerifyDevice)
		verificationData = findViewById(R.id.matrixVerificationData)
		verificationActions = findViewById(R.id.matrixVerificationActions)
		confirmVerification = findViewById(R.id.matrixConfirmVerification)
		declineVerification = findViewById(R.id.matrixDeclineVerification)
		cancelVerification = findViewById(R.id.matrixCancelVerification)
		verifyDevice.setOnClickListener { model.startVerification() }
		confirmVerification.setOnClickListener { model.confirmVerification() }
		declineVerification.setOnClickListener { model.declineVerification() }
		cancelVerification.setOnClickListener { model.cancelVerification() }
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
		signOut.setOnClickListener {
			if (model.state.value?.busy == true) return@setOnClickListener
			MatrixSignOutDialogFragment().show(supportFragmentManager, MatrixSignOutDialogFragment.TAG)
		}
		model.start()
	}

	internal fun confirmSignOut(confirmed: Boolean) {
		model.logout(confirmed)
	}

	private fun render(state: MatrixSetupState) {
		val step = state.snapshot?.let(::setupStep)
		homeserver.visibility = if (step == MatrixSetupStep.HOMESERVER) View.VISIBLE else View.GONE
		val credentialsVisible = step == MatrixSetupStep.CREDENTIALS
		username.visibility = if (credentialsVisible) View.VISIBLE else View.GONE
		password.visibility = username.visibility
		if (!credentialsVisible) {
			username.text.clear()
			password.text.clear()
		}
		signOut.visibility = if (step == MatrixSetupStep.READY) View.VISIBLE else View.GONE
		signOut.isEnabled = !state.busy
		roomIdentifier.visibility = signOut.visibility
		joinRoom.visibility = signOut.visibility
		roomIdentifier.isEnabled = !state.busy
		joinRoom.isEnabled = !state.busy
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
			state.logoutFailed -> R.string.matrix_sign_out_failed
			state.actionFailed && step == MatrixSetupStep.READY -> R.string.matrix_join_failed
			state.roomJoined -> R.string.matrix_room_joined
			step == MatrixSetupStep.READY -> R.string.matrix_ready
			error == RecoverableErrorDetail.UNSUPPORTED_AUTH_STEP -> R.string.matrix_unsupported
			error == RecoverableErrorDetail.DEVICE_KEYSTORE_UNAVAILABLE ||
				error == RecoverableErrorDetail.PERSISTED_SESSION_IDENTITY_UNVERIFIED ->
				R.string.matrix_store_failed
			error == RecoverableErrorDetail.SESSION_EXPIRED -> R.string.matrix_session_expired
			step == MatrixSetupStep.UNAVAILABLE -> R.string.matrix_unavailable
			state.actionFailed || (error != null && error != RecoverableErrorDetail.NONE) ->
				R.string.matrix_setup_failed
			credentialsVisible -> R.string.matrix_sign_in
			else -> R.string.matrix_homeserver
		}
		status.setText(message)
		val userId = signedInUserId(state.snapshot)
		account.visibility = if (userId != null) View.VISIBLE else View.GONE
		account.text = userId?.let { getString(R.string.matrix_signed_in_as, it) }.orEmpty()

		val ready = step == MatrixSetupStep.READY
		deviceTrust.visibility = if (ready) View.VISIBLE else View.GONE
		deviceTrust.setText(
			if (state.deviceTrustState == MatrixDeviceTrustState.VERIFIED) {
				R.string.matrix_device_verified
			} else {
				R.string.matrix_device_unverified
			},
		)
		when (val vState = if (ready) state.verificationState else MatrixVerificationState.Idle) {
			is MatrixVerificationState.Idle -> {
				verifyDevice.visibility =
					if (ready && state.deviceTrustState != MatrixDeviceTrustState.VERIFIED) {
						View.VISIBLE
					} else {
						View.GONE
					}
				verifyDevice.isEnabled = !state.busy
				verificationData.visibility = View.GONE
				verificationActions.visibility = View.GONE
				cancelVerification.visibility = View.GONE
			}
			is MatrixVerificationState.Requesting, is MatrixVerificationState.AwaitingPeer -> {
				verifyDevice.visibility = View.GONE
				verificationData.visibility = View.VISIBLE
				verificationData.setText(R.string.matrix_verification_waiting)
				verificationActions.visibility = View.GONE
				cancelVerification.visibility = View.VISIBLE
				cancelVerification.isEnabled = !state.busy
			}
			is MatrixVerificationState.Comparing -> {
				verifyDevice.visibility = View.GONE
				verificationData.visibility = View.VISIBLE
				verificationData.text = vState.emojis.joinToString("  ") {
					"${it.symbol} ${it.description}"
				}
				verificationActions.visibility = View.VISIBLE
				confirmVerification.isEnabled = !state.busy
				declineVerification.isEnabled = !state.busy
				cancelVerification.visibility = View.VISIBLE
				cancelVerification.isEnabled = !state.busy
			}
			is MatrixVerificationState.Verified -> {
				verifyDevice.visibility = View.GONE
				verificationData.visibility = View.VISIBLE
				verificationData.setText(R.string.matrix_verification_success)
				verificationActions.visibility = View.GONE
				cancelVerification.visibility = View.GONE
			}
			is MatrixVerificationState.Cancelled,
			is MatrixVerificationState.Failed,
			is MatrixVerificationState.TimedOut,
			-> {
				verifyDevice.visibility = View.VISIBLE
				verifyDevice.isEnabled = !state.busy
				verificationData.visibility = View.VISIBLE
				val res = when (vState) {
					MatrixVerificationState.Failed -> R.string.matrix_verification_failed
					MatrixVerificationState.TimedOut -> R.string.matrix_verification_timed_out
					else -> R.string.matrix_verification_cancelled
				}
				verificationData.setText(res)
				verificationActions.visibility = View.GONE
				cancelVerification.visibility = View.GONE
			}
		}

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

class MatrixSignOutDialogFragment : DialogFragment() {
	companion object {
		const val TAG = "MatrixSignOutDialogFragment"
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val activity = requireActivity() as MatrixSetupActivity
		return AlertDialog.Builder(activity)
			.setTitle(R.string.matrix_sign_out)
			.setMessage(R.string.matrix_sign_out_confirmation)
			.setNegativeButton(R.string.cancel) { _, _ -> activity.confirmSignOut(false) }
			.setPositiveButton(R.string.matrix_sign_out) { _, _ -> activity.confirmSignOut(true) }
			.create()
	}
}
