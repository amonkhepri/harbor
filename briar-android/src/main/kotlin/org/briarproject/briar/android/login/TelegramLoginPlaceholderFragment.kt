@file:Suppress("ktlint:standard:function-naming")

package org.briarproject.briar.android.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import org.briarproject.briar.R
import org.briarproject.briar.android.activity.ActivityComponent
import org.briarproject.briar.android.fragment.BaseFragment
import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail
import org.briarproject.briar.api.telegram.TelegramAuthState
import javax.inject.Inject

const val TELEGRAM_LOGIN_IDENTIFIER_STEP_TAG = "telegram_login_identifier_step"
const val TELEGRAM_LOGIN_CODE_STEP_TAG = "telegram_login_code_step"
const val TELEGRAM_LOGIN_PASSWORD_STEP_TAG = "telegram_login_password_step"
const val TELEGRAM_LOGIN_CONFIRMATION_TAG = "telegram_login_confirmation"
const val TELEGRAM_LOGIN_IDENTIFIER_TAG = "telegram_login_identifier"
const val TELEGRAM_LOGIN_CODE_TAG = "telegram_login_code"
const val TELEGRAM_LOGIN_PASSWORD_TAG = "telegram_login_password"
const val TELEGRAM_LOGIN_CONTINUE_TAG = "btn_telegram_login_continue"
const val TELEGRAM_LOGIN_CODE_CONTINUE_TAG = "btn_telegram_login_code_continue"
const val TELEGRAM_LOGIN_PASSWORD_CONTINUE_TAG = "btn_telegram_login_password_continue"
const val TELEGRAM_LOGIN_CONFIRMATION_CONTINUE_TAG =
	"btn_telegram_login_confirmation_continue"
const val TELEGRAM_LOGIN_CONFIRMATION_BACK_TAG =
	"btn_telegram_login_confirmation_back"
const val TELEGRAM_LOGIN_BACK_TAG = "btn_telegram_login_back"

class TelegramLoginPlaceholderFragment : BaseFragment() {

	@Inject
	lateinit var viewModelFactory: ViewModelProvider.Factory

	private lateinit var viewModel: StartupViewModel

	override fun injectFragment(component: ActivityComponent) {
		component.inject(this)
		viewModel = ViewModelProvider(
			requireActivity(),
			viewModelFactory,
		)[StartupViewModel::class.java]
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		setContent {
			TelegramLoginTheme {
				TelegramLoginScreen(viewModel)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		requireActivity().setTitle(R.string.telegram_connector_login_title)
	}

	override fun getUniqueTag(): String = TAG

	companion object {
		const val TAG = "org.briarproject.briar.android.login.TelegramLoginPlaceholderFragment"

		@JvmStatic
		fun newInstance() = TelegramLoginPlaceholderFragment()
	}
}

@Composable
internal fun TelegramLoginScreen(viewModel: StartupViewModel) {
	val authSnapshot by rememberTelegramAuthSnapshot(viewModel)
	val authState = authSnapshot.authState
	val errorDetail = authSnapshot.errorDetail
	var identifier by remember { mutableStateOf(viewModel.getTelegramLoginIdentifier()) }
	var code by remember { mutableStateOf(viewModel.getTelegramLoginCode()) }
	var password by remember { mutableStateOf(viewModel.getTelegramLoginPassword()) }

	LaunchedEffect(authSnapshot.revision) {
		identifier = viewModel.getTelegramLoginIdentifier()
		code = viewModel.getTelegramLoginCode()
		password = viewModel.getTelegramLoginPassword()
	}

	val hasIdentifier = identifier.trim().isNotEmpty()
	val hasCode = code.trim().isNotEmpty()
	val hasPassword = password.trim().isNotEmpty()
	val missingTdlib = authState == TelegramAuthState.RECOVERABLE_ERROR &&
		errorDetail == RecoverableErrorDetail.MISSING_TDLIB
	val deviceKeystoreUnavailable = authState == TelegramAuthState.RECOVERABLE_ERROR &&
		errorDetail == RecoverableErrorDetail.DEVICE_KEYSTORE_UNAVAILABLE
	val tdlibDatabaseKeyMismatch = authState == TelegramAuthState.RECOVERABLE_ERROR &&
		errorDetail == RecoverableErrorDetail.TDLIB_DATABASE_KEY_MISMATCH

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
			.verticalScroll(rememberScrollState())
			.padding(24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Column(
			modifier = Modifier.widthIn(max = 480.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Image(
				painter = painterResource(R.drawable.navigation_drawer_mark),
				contentDescription = stringResource(R.string.app_name),
				modifier = Modifier.size(88.dp),
			)
			Spacer(Modifier.height(24.dp))
			Text(
				text = stringResource(R.string.telegram_connector_login_title),
				style = MaterialTheme.typography.headlineSmall,
				color = MaterialTheme.colorScheme.onBackground,
				textAlign = TextAlign.Center,
			)
			Spacer(Modifier.height(16.dp))
			Text(
				text = stringResource(loginMessage(authState, errorDetail)),
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onBackground,
				textAlign = TextAlign.Center,
				modifier = Modifier.fillMaxWidth(),
			)
			Spacer(Modifier.height(24.dp))

			when {
				authState == TelegramAuthState.CODE_ENTRY ||
					authState == TelegramAuthState.RECOVERABLE_ERROR &&
					errorDetail == RecoverableErrorDetail.INVALID_CODE -> {
					CodeStep(
						code = code,
						onCodeChange = {
							code = it
							viewModel.setTelegramLoginCode(it)
						},
						enabled = hasCode,
						onContinue = viewModel::submitTelegramLoginCode,
					)
				}
				authState == TelegramAuthState.PASSWORD_ENTRY ||
					authState == TelegramAuthState.RECOVERABLE_ERROR &&
					errorDetail == RecoverableErrorDetail.INVALID_PASSWORD -> {
					PasswordStep(
						password = password,
						onPasswordChange = {
							password = it
							viewModel.setTelegramLoginPassword(it)
						},
						enabled = hasPassword,
						onContinue = viewModel::submitTelegramLoginPassword,
					)
				}
				authState == TelegramAuthState.READY -> {
					ConfirmationStep(
						identifier = identifier.trim(),
						onContinue = viewModel::completeTelegramLoginConfirmation,
						onBack = viewModel::showTelegramLoginIdentifierStep,
					)
				}
				else -> {
					IdentifierStep(
						identifier = identifier,
						onIdentifierChange = {
							identifier = it
							viewModel.setTelegramLoginIdentifier(it)
						},
						enabled = hasIdentifier &&
							!missingTdlib &&
							!deviceKeystoreUnavailable &&
							!tdlibDatabaseKeyMismatch,
						onContinue = viewModel::submitTelegramLoginIdentifier,
					)
				}
			}

			Spacer(Modifier.height(24.dp))
			Button(
				onClick = viewModel::showPasswordFragment,
				modifier = Modifier
					.fillMaxWidth()
					.testTag(TELEGRAM_LOGIN_BACK_TAG),
			) {
				Text(stringResource(R.string.telegram_connector_login_back_button))
			}
		}
	}
}

private data class TelegramAuthSnapshot(
	val authState: TelegramAuthState,
	val errorDetail: RecoverableErrorDetail,
	val revision: Int,
)

@Composable
private fun rememberTelegramAuthSnapshot(viewModel: StartupViewModel): State<TelegramAuthSnapshot> {
	val lifecycleOwner = LocalLifecycleOwner.current
	val authLiveData = viewModel.getTelegramAuthState()
	val snapshot = remember(viewModel, authLiveData) {
		mutableStateOf(
			TelegramAuthSnapshot(
				authLiveData.value ?: TelegramAuthState.CLOSED,
				viewModel.getTelegramRecoverableErrorDetail(),
				0,
			),
		)
	}
	DisposableEffect(viewModel, authLiveData, lifecycleOwner) {
		var revision = snapshot.value.revision
		val observer = Observer<TelegramAuthState> { authState ->
			revision++
			snapshot.value = TelegramAuthSnapshot(
				authState,
				viewModel.getTelegramRecoverableErrorDetail(),
				revision,
			)
		}
		authLiveData.observe(lifecycleOwner, observer)
		onDispose { authLiveData.removeObserver(observer) }
	}
	return snapshot
}

@Composable
private fun IdentifierStep(
	identifier: String,
	onIdentifierChange: (String) -> Unit,
	enabled: Boolean,
	onContinue: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.testTag(TELEGRAM_LOGIN_IDENTIFIER_STEP_TAG),
	) {
		OutlinedTextField(
			value = identifier,
			onValueChange = onIdentifierChange,
			label = {
				Text(stringResource(R.string.telegram_connector_login_identifier_hint))
			},
			singleLine = true,
			colors = telegramTextFieldColors(),
			modifier = Modifier
				.fillMaxWidth()
				.testTag(TELEGRAM_LOGIN_IDENTIFIER_TAG),
		)
		Spacer(Modifier.height(16.dp))
		Button(
			onClick = onContinue,
			enabled = enabled,
			modifier = Modifier
				.fillMaxWidth()
				.testTag(TELEGRAM_LOGIN_CONTINUE_TAG),
		) {
			Text(stringResource(R.string.telegram_connector_login_continue_button))
		}
	}
}

@Composable
private fun CodeStep(
	code: String,
	onCodeChange: (String) -> Unit,
	enabled: Boolean,
	onContinue: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.testTag(TELEGRAM_LOGIN_CODE_STEP_TAG),
	) {
		OutlinedTextField(
			value = code,
			onValueChange = onCodeChange,
			label = {
				Text(stringResource(R.string.telegram_connector_login_code_hint))
			},
			singleLine = true,
			colors = telegramTextFieldColors(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier
				.fillMaxWidth()
				.testTag(TELEGRAM_LOGIN_CODE_TAG),
		)
		Spacer(Modifier.height(16.dp))
		Button(
			onClick = onContinue,
			enabled = enabled,
			modifier = Modifier
				.fillMaxWidth()
				.testTag(TELEGRAM_LOGIN_CODE_CONTINUE_TAG),
		) {
			Text(stringResource(R.string.telegram_connector_login_continue_button))
		}
	}
}

@Composable
private fun PasswordStep(
	password: String,
	onPasswordChange: (String) -> Unit,
	enabled: Boolean,
	onContinue: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.testTag(TELEGRAM_LOGIN_PASSWORD_STEP_TAG),
	) {
		OutlinedTextField(
			value = password,
			onValueChange = onPasswordChange,
			label = {
				Text(stringResource(R.string.telegram_connector_login_password_hint))
			},
			singleLine = true,
			colors = telegramTextFieldColors(),
			visualTransformation = PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
			modifier = Modifier
				.fillMaxWidth()
				.testTag(TELEGRAM_LOGIN_PASSWORD_TAG),
		)
		Spacer(Modifier.height(16.dp))
		Button(
			onClick = onContinue,
			enabled = enabled,
			modifier = Modifier
				.fillMaxWidth()
				.testTag(TELEGRAM_LOGIN_PASSWORD_CONTINUE_TAG),
		) {
			Text(stringResource(R.string.telegram_connector_login_continue_button))
		}
	}
}

@Composable
private fun ConfirmationStep(identifier: String, onContinue: () -> Unit, onBack: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.testTag(TELEGRAM_LOGIN_CONFIRMATION_TAG),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(
			text = stringResource(
				R.string.telegram_connector_login_confirmation_message,
				identifier,
			),
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onBackground,
			textAlign = TextAlign.Center,
			modifier = Modifier.fillMaxWidth(),
		)
		Spacer(Modifier.height(16.dp))
		Button(
			onClick = onContinue,
			modifier = Modifier
				.fillMaxWidth()
				.testTag(TELEGRAM_LOGIN_CONFIRMATION_CONTINUE_TAG),
		) {
			Text(
				stringResource(
					R.string.telegram_connector_login_confirmation_continue_button,
				),
			)
		}
		Spacer(Modifier.height(16.dp))
		Button(
			onClick = onBack,
			modifier = Modifier.testTag(TELEGRAM_LOGIN_CONFIRMATION_BACK_TAG),
		) {
			Text(
				stringResource(
					R.string.telegram_connector_login_confirmation_back_button,
				),
			)
		}
	}
}

@Composable
private fun TelegramLoginTheme(content: @Composable () -> Unit) {
	MaterialTheme(
		colorScheme = telegramLoginColorScheme(),
		content = content,
	)
}

@Composable
private fun telegramLoginColorScheme(): ColorScheme {
	val primary = colorResource(R.color.md_theme_primary)
	val onPrimary = colorResource(R.color.md_theme_onPrimary)
	val background = colorResource(R.color.window_background)
	val onBackground = colorResource(R.color.md_theme_onBackground)
	val surface = colorResource(R.color.card_background)
	val onSurface = colorResource(R.color.md_theme_onSurface)
	val surfaceVariant = colorResource(R.color.md_theme_surfaceDim)
	val outline = colorResource(R.color.divider)
	val error = colorResource(R.color.md_theme_error)

	return if (isSystemInDarkTheme()) {
		darkColorScheme(
			primary = primary,
			onPrimary = onPrimary,
			background = background,
			onBackground = onBackground,
			surface = surface,
			onSurface = onSurface,
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = onSurface,
			outline = outline,
			error = error,
		)
	} else {
		lightColorScheme(
			primary = primary,
			onPrimary = onPrimary,
			background = background,
			onBackground = onBackground,
			surface = surface,
			onSurface = onSurface,
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = onSurface,
			outline = outline,
			error = error,
		)
	}
}

@Composable
private fun telegramTextFieldColors() = OutlinedTextFieldDefaults.colors(
	focusedTextColor = MaterialTheme.colorScheme.onSurface,
	unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
	disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
	focusedContainerColor = MaterialTheme.colorScheme.surface,
	unfocusedContainerColor = MaterialTheme.colorScheme.surface,
	disabledContainerColor = MaterialTheme.colorScheme.surface,
	cursorColor = MaterialTheme.colorScheme.primary,
	focusedBorderColor = MaterialTheme.colorScheme.primary,
	unfocusedBorderColor = MaterialTheme.colorScheme.outline,
	disabledBorderColor = MaterialTheme.colorScheme.outline,
	focusedLabelColor = MaterialTheme.colorScheme.primary,
	unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
	disabledLabelColor =
	MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
)

private fun loginMessage(authState: TelegramAuthState, errorDetail: RecoverableErrorDetail): Int {
	if (authState != TelegramAuthState.RECOVERABLE_ERROR) {
		return R.string.telegram_connector_login_message
	}
	return when (errorDetail) {
		RecoverableErrorDetail.MISSING_TDLIB ->
			R.string.telegram_connector_login_tdlib_missing_message
		RecoverableErrorDetail.MISSING_API_CREDENTIALS ->
			R.string.telegram_connector_login_api_credentials_missing_message
		RecoverableErrorDetail.INVALID_IDENTIFIER ->
			R.string.telegram_connector_login_identifier_invalid_message
		RecoverableErrorDetail.INVALID_CODE ->
			R.string.telegram_connector_login_code_invalid_message
		RecoverableErrorDetail.INVALID_PASSWORD ->
			R.string.telegram_connector_login_password_invalid_message
		RecoverableErrorDetail.UNSUPPORTED_AUTH_STEP ->
			R.string.telegram_connector_login_unsupported_auth_step_message
		RecoverableErrorDetail.DEVICE_KEYSTORE_UNAVAILABLE ->
			R.string.telegram_connector_login_device_keystore_unavailable_message
		RecoverableErrorDetail.TDLIB_DATABASE_KEY_MISMATCH ->
			R.string.telegram_connector_login_tdlib_key_mismatch_message
		else -> R.string.telegram_connector_login_retry_message
	}
}
