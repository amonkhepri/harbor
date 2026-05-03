package org.briarproject.briar.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class TelegramFeatureFlagsDefaultsTest {

	@Test
	@Throws(IOException::class)
	fun testFeatureFlagsExposeTelegramConnectorGate() {
		assertFileContains(
			"../bramble-api/src/main/java/org/briarproject/bramble/api/FeatureFlags.java",
			"boolean shouldEnableTelegramConnector();",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testAndroidAndHeadlessDefaultTelegramConnectorToDisabled() {
		assertFileContains(
			"build.gradle",
			"project.findProperty('harbor.telegramConnector.enabled') ?: 'false'",
		)
		assertFileContains(
			"build.gradle",
			"buildConfigField \"boolean\", \"TELEGRAM_CONNECTOR_ENABLED\"",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/AppModule.java",
			"return BuildConfig.TELEGRAM_CONNECTOR_ENABLED;",
		)
		assertFileContains(
			"../briar-headless/src/main/java/org/briarproject/briar/headless/HeadlessModule.kt",
			"override fun shouldEnableTelegramConnector() = false",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTestFeatureFlagsDefaultTelegramConnectorToDisabled() {
		assertFileContains(
			"../bramble-core/src/test/java/org/briarproject/bramble/test/TestFeatureFlagModule.java",
			"public boolean shouldEnableTelegramConnector() {\n\t\t\t\treturn false;",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramConnectorStubIsWiredIntoCoreGraph() {
		assertFileContains(
			"../briar-api/build.gradle",
			"apply plugin: 'org.jetbrains.kotlin.jvm'",
		)
		assertFileContains(
			"../briar-api/build.gradle",
			"api \"org.jetbrains.kotlin:kotlin-stdlib:${'$'}kotlin_version\"",
		)
		assertFileContains(
			"../briar-api/src/main/kotlin/org/briarproject/briar/api/telegram/TelegramConnector.kt",
			"interface TelegramConnector {\n\tfun isEnabled(): Boolean\n}",
		)
		assertFileMissing(
			"../briar-api/src/main/java/org/briarproject/briar/api/telegram/TelegramConnector.java",
		)
		assertFileContains(
			"../briar-core/src/main/kotlin/org/briarproject/briar/telegram/NoOpTelegramConnector.kt",
			"class NoOpTelegramConnector : TelegramConnector {",
		)
		assertFileMissing(
			"../briar-core/src/main/java/org/briarproject/briar/telegram/NoOpTelegramConnector.java",
		)
		assertFileContains(
			"../briar-core/src/main/kotlin/org/briarproject/briar/telegram/StubTelegramConnector.kt",
			"class StubTelegramConnector : TelegramConnector {",
		)
		assertFileMissing(
			"../briar-core/src/main/java/org/briarproject/briar/telegram/StubTelegramConnector.java",
		)
		assertFileContainsAll(
			"../briar-core/src/main/kotlin/org/briarproject/briar/telegram/TelegramModule.kt",
			"fun provideTelegramConnector(featureFlags: FeatureFlags): TelegramConnector =",
			"StubTelegramConnector()",
			"NoOpTelegramConnector()",
		)
		assertFileMissing(
			"../briar-core/src/main/java/org/briarproject/briar/telegram/TelegramModule.java",
		)
		assertFileContains(
			"../briar-core/src/main/java/org/briarproject/briar/BriarCoreModule.java",
			"TelegramModule.class,",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/AndroidComponent.java",
			"TelegramConnector telegramConnector();",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramAuthSessionSeamIsWiredWithoutTdlibTypes() {
		assertFileContains(
			"../briar-api/src/main/kotlin/org/briarproject/briar/api/telegram/TelegramAuthState.kt",
			"enum class TelegramAuthState {\n\tIDENTIFIER_ENTRY,\n\tCODE_ENTRY,\n\tPASSWORD_ENTRY,\n\tREADY,\n\tCLOSED,\n\tRECOVERABLE_ERROR\n}",
		)
		assertFileMissing(
			"../briar-api/src/main/java/org/briarproject/briar/api/telegram/TelegramAuthState.java",
		)
		assertFileContains(
			"../briar-api/src/main/kotlin/org/briarproject/briar/api/telegram/TelegramAuthSession.kt",
			"interface TelegramAuthSession {\n\tenum class RecoverableErrorDetail {\n\t\tNONE,\n\t\tMISSING_TDLIB,\n\t\tINVALID_IDENTIFIER,\n\t\tINVALID_CODE,\n\t\tINVALID_PASSWORD\n\t}\n\n\tfun getCurrentState(): TelegramAuthState\n\tfun getRecoverableErrorDetail(): RecoverableErrorDetail\n\tfun start()\n\tfun submitIdentifier(identifier: String)\n\tfun submitCode(code: String)\n\tfun submitPassword(password: String)\n\tfun close()\n}",
		)
		assertFileMissing(
			"../briar-api/src/main/java/org/briarproject/briar/api/telegram/TelegramAuthSession.java",
		)
		assertFileContainsAll(
			"../briar-core/src/main/kotlin/org/briarproject/briar/telegram/TelegramModule.kt",
			"fun provideTelegramAuthSession(",
			"featureFlags: FeatureFlags,",
			"databaseConfig: DatabaseConfig,",
			"TelegramAuthSessionImpl(",
			"StubTelegramTdlibLoginClient(",
			"File(databaseConfig.databaseDirectory, \"tdlib\")",
			"TelegramAuthSessionImpl(NoOpTelegramTdlibLoginClient())",
		)
		assertFileContains(
			"../briar-core/src/main/kotlin/org/briarproject/briar/telegram/TelegramAuthSessionImpl.kt",
			"class TelegramAuthSessionImpl(",
		)
		assertFileMissing(
			"../briar-core/src/main/java/org/briarproject/briar/telegram/TelegramAuthSessionImpl.java",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramAuthSessionUsesHarborOwnedTdlibFacade() {
		assertFileContains(
			"../briar-core/build.gradle",
			"apply plugin: 'org.jetbrains.kotlin.jvm'",
		)
		assertFileContains(
			"../briar-core/build.gradle",
			"implementation \"org.jetbrains.kotlin:kotlin-stdlib:${'$'}kotlin_version\"",
		)
		assertFileContains(
			"../briar-core/src/main/kotlin/org/briarproject/briar/telegram/TelegramTdlibLoginClient.kt",
			"interface TelegramTdlibLoginClient {\n\tfun start(): TelegramAuthState\n\tfun getRecoverableErrorDetail(): RecoverableErrorDetail\n\tfun submitIdentifier(identifier: String): TelegramAuthState\n\tfun submitCode(code: String): TelegramAuthState\n\tfun submitPassword(password: String): TelegramAuthState\n\tfun close(): TelegramAuthState\n}",
		)
		assertFileMissing(
			"../briar-core/src/main/java/org/briarproject/briar/telegram/TelegramTdlibLoginClient.java",
		)
		assertFileContainsAll(
			"../briar-core/src/main/kotlin/org/briarproject/briar/telegram/TelegramAuthSessionImpl.kt",
			"override fun getRecoverableErrorDetail(): RecoverableErrorDetail =",
			"return recoverableError(RecoverableErrorDetail.MISSING_TDLIB)",
			"return recoverableError(RecoverableErrorDetail.INVALID_IDENTIFIER)",
			"\"AuthorizationStateWaitTdlibParameters\",",
			"\"AuthorizationStateWaitPhoneNumber\" -> clearRecoverableErrorDetail(TelegramAuthState.IDENTIFIER_ENTRY)",
			"send(createSetTdlibParametersRequest())",
			"sendReturnsError(createSetAuthenticationPhoneNumberRequest(identifier))",
			"sendReturnsError(createCheckAuthenticationCodeRequest(code))",
			"return recoverableError(RecoverableErrorDetail.INVALID_CODE)",
			"sendReturnsError(createCheckAuthenticationPasswordRequest(password))",
			"return recoverableError(RecoverableErrorDetail.INVALID_PASSWORD)",
			"private class PendingAuthorizationUpdate {",
			"pendingAuthorizationUpdate = it",
			"private fun closeTdlibClient() {",
			"lastAuthorizationStateClassName = \"\"",
			"completePendingAuthorizationUpdate(\"AuthorizationStateClosed\")",
			"val client = tdlibClient ?: return",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testBriarAndroidCanConsumePrebuiltTdlibAndroidArtifacts() {
		assertFileContains(
			"build.gradle",
			"def tdlibDir = rootProject.file('third_party/tdlib')\ndef tdlibJavaDir = new File(tdlibDir, 'java')\ndef tdlibJniLibsDir = new File(tdlibDir, 'libs')",
		)
		assertFileContains(
			"build.gradle",
			"java.srcDirs += [tdlibJavaDir]\n\t\t\tjniLibs.srcDirs += [tdlibJniLibsDir]",
		)
		assertFileContains(
			"../third_party/tdlib/README.md",
			"Harbor consumes prebuilt TDLib Android output from this directory.",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testConnectionsSettingsCanObserveTelegramConnectorAvailability() {
		assertConnectionsFragmentContainsAll(
			"TelegramConnector telegramConnector;",
			"telegramStatus.setVisible(telegramConnector.isEnabled());",
			"telegramStatus.setSummary(requireSettingsActivity()\n\t\t\t\t.isTelegramConnectorReady()\n\t\t\t\t? R.string.telegram_connector_settings_ready_summary\n\t\t\t\t: R.string.telegram_connector_settings_summary);",
		)
		assertFileContains(
			"src/main/kotlin/org/briarproject/briar/android/settings/SettingsActivity.kt",
			"fun isTelegramConnectorReady(): Boolean =\n\t\t\tgetBriarController().isTelegramConnectorReady()",
		)
		assertFileContains(
			"src/main/res/xml/settings_connections.xml",
			"android:key=\"pref_key_telegram_status\"",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testBriarControllerExposesTelegramConnectorReadinessSeam() {
		assertFileContains(
			"src/main/kotlin/org/briarproject/briar/android/controller/BriarController.kt",
			"fun isTelegramConnectorReady(): Boolean",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/controller/BriarControllerImpl.java",
			"private final TelegramConnector telegramConnector;",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/controller/BriarControllerImpl.java",
			"public boolean isTelegramConnectorReady() {\n\t\treturn accountSignedIn() && telegramConnector.isEnabled();\n\t}",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testBriarControllerExposesTelegramIdentityStagingSeam() {
		assertFileContains(
			"src/main/kotlin/org/briarproject/briar/android/controller/BriarController.kt",
			"fun getTelegramLinkedIdentity(handler: ResultHandler<String>)",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/controller/BriarControllerImpl.java",
			"public void getTelegramLinkedIdentity(ResultHandler<String> handler) {",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/controller/BriarControllerImpl.java",
			"handler.onResult(settings.get(\"pref_key_telegram_linked_identity\"));",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testBriarActivityLoadsTelegramIdentityDuringResume() {
		assertBriarActivityContainsAll(
			"briarController.getTelegramLinkedIdentity(new UiResultHandler<String>(this) {",
			"onTelegramLinkedIdentityAvailable(linkedIdentity);",
			"protected void onTelegramLinkedIdentityAvailable(\n\t\t\t@Nullable String linkedIdentity) {",
			"protected void showTelegramLinkedIdentitySubtitle(\n\t\t\t@Nullable String linkedIdentity) {",
			"if (getBriarController().isTelegramConnectorReady()\n\t\t\t\t&& linkedIdentity != null && !linkedIdentity.isEmpty()) {",
			"actionBar.setSubtitle(getString(\n\t\t\t\t\tR.string.telegram_connector_transports_subtitle,\n\t\t\t\t\tlinkedIdentity));",
			"actionBar.setSubtitle(null);",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramIdentityConsumersSurfaceOutsideSettings() {
		assertTelegramSubtitleConsumer(
			"src/main/java/org/briarproject/briar/android/navdrawer/TransportsActivity.java",
		)
		assertTelegramSubtitleConsumer(
			"src/main/java/org/briarproject/briar/android/navdrawer/NavDrawerActivity.java",
		)
		assertTelegramSubtitleConsumer(
			"src/main/java/org/briarproject/briar/android/hotspot/HotspotActivity.java",
		)
		assertTelegramSubtitleConsumer(
			"src/main/java/org/briarproject/briar/android/contact/add/remote/PendingContactListActivity.java",
		)
		assertTelegramSubtitleConsumer(
			"src/main/java/org/briarproject/briar/android/contact/add/remote/AddContactActivity.java",
		)
		assertTelegramSubtitleConsumer(
			"src/main/java/org/briarproject/briar/android/contact/add/nearby/AddNearbyContactActivity.java",
		)
		assertTelegramSubtitleConsumer(
			"src/main/kotlin/org/briarproject/briar/android/introduction/IntroductionActivity.kt",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testPasswordFragmentExposesTelegramLoginPlaceholder() {
		assertFileContainsAll(
			"src/main/java/org/briarproject/briar/android/login/StartupViewModel.java",
			"private final FeatureFlags featureFlags;",
			"FeatureFlags featureFlags,\n\t\t\tTelegramAuthSession telegramAuthSession) {",
			"this.featureFlags = featureFlags;",
			"this.telegramAuthSession = telegramAuthSession;",
			"boolean shouldShowTelegramLogin() {\n\t\treturn featureFlags.shouldEnableTelegramConnector();\n\t}",
		)
		assertFileContainsAll(
			"src/main/java/org/briarproject/briar/android/login/PasswordFragment.java",
			"telegramLoginButton = v.findViewById(R.id.btn_telegram_login);",
			"telegramLoginButton.setVisibility(\n\t\t\t\tviewModel.shouldShowTelegramLogin() ? VISIBLE : GONE);",
			"telegramLoginButton.setOnClickListener(\n\t\t\t\tview -> onTelegramLoginClick());",
		)
		assertFileContains(
			"src/main/res/layout/fragment_password.xml",
			"android:id=\"@+id/btn_telegram_login\"",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testStartupActivityOwnsTelegramLoginPlaceholderRouting() {
		assertStartupViewModelContainsAll(
			"enum State {SIGNED_OUT, TELEGRAM_LOGIN, SIGNED_IN, STARTING, MIGRATING, COMPACTING, STARTED}",
			"void showTelegramLoginPlaceholder() {\n\t\tpendingTelegramLinkedIdentity = telegramLoginCode = telegramLoginPassword = \"\";\n\t\ttelegramAuthSession.start();\n\t\ttelegramAuthState.setValue(telegramAuthSession.getCurrentState());\n\t\tstate.setValue(TELEGRAM_LOGIN);\n\t}",
			"void showPasswordFragment() {\n\t\ttelegramLoginIdentifier = telegramLoginCode = telegramLoginPassword = \"\";\n\t\ttelegramAuthSession.close();\n\t\ttelegramAuthState.setValue(telegramAuthSession.getCurrentState());\n\t\tstate.setValue(SIGNED_OUT);\n\t}",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/login/PasswordFragment.java",
			"private void onTelegramLoginClick() {\n\t\tviewModel.showTelegramLoginPlaceholder();\n\t}",
		)
		assertStartupActivityContainsAll(
			"if (state == SIGNED_OUT) {",
			"showPasswordFragment();",
			"} else if (state == TELEGRAM_LOGIN) {\n\t\t\tshowTelegramLoginPlaceholder();\n\t\t}",
			"if (viewModel.getState().getValue() == TELEGRAM_LOGIN) {\n\t\t\tif (viewModel.isShowingTelegramLoginConfirmation()) {\n\t\t\t\tviewModel.showTelegramLoginIdentifierStep();\n\t\t\t\treturn;\n\t\t\t}\n\t\t\tviewModel.showPasswordFragment();\n\t\t\treturn;\n\t\t}",
			"private void showPasswordFragment() {",
			"private void showTelegramLoginPlaceholder() {",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testStartupViewModelClosesTelegramAuthSessionWhenCleared() {
		assertStartupViewModelContainsAll(
			"@Override\n\tprotected void onCleared() {\n\t\ttelegramAuthSession.close();\n\t\teventBus.removeListener(this);\n\t}",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramLoginPlaceholderStagesIdentifierInput() {
		assertStartupViewModelContainsAll(
			"private String telegramLoginIdentifier = \"\";",
			"String getTelegramLoginIdentifier() {\n\t\treturn telegramLoginIdentifier;\n\t}",
			"void setTelegramLoginIdentifier(String identifier) {\n\t\ttelegramLoginIdentifier = identifier;\n\t}",
		)
		assertTelegramLoginPlaceholderFragmentContainsAll(
			"class TelegramLoginPlaceholderFragment : BaseFragment()",
			"ComposeView(requireContext())",
			"TELEGRAM_LOGIN_IDENTIFIER_STEP_TAG",
			"TELEGRAM_LOGIN_IDENTIFIER_TAG",
			"OutlinedTextField(",
			"viewModel.setTelegramLoginIdentifier(it)",
			"viewModel::submitTelegramLoginIdentifier",
		)
		assertFileMissing(
			"src/main/java/org/briarproject/briar/android/login/TelegramLoginPlaceholderFragment.java",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramLoginPlaceholderStagesCodeEntryStep() {
		assertFileContainsAll(
			"src/main/java/org/briarproject/briar/android/login/StartupViewModel.java",
			"private String telegramLoginCode = \"\";",
			"String getTelegramLoginCode() {\n\t\treturn telegramLoginCode;\n\t}",
			"void setTelegramLoginCode(String code) {\n\t\ttelegramLoginCode = code;\n\t}",
			"telegramAuthSession.submitCode(telegramLoginCode.trim());",
			"telegramAuthState.setValue(telegramAuthSession.getCurrentState());",
			"telegramLoginCode = telegramLoginPassword = \"\";\n\t\ttelegramAuthSession.close();\n\t\ttelegramAuthSession.start();",
		)
		assertTelegramLoginPlaceholderFragmentContainsAll(
			"TELEGRAM_LOGIN_CODE_STEP_TAG",
			"TELEGRAM_LOGIN_CODE_TAG",
			"CodeStep(",
			"viewModel.setTelegramLoginCode(it)",
			"viewModel::submitTelegramLoginCode",
		)
		assertFileMissing("src/main/res/layout/fragment_telegram_login_placeholder.xml")
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramLoginPlaceholderStagesPasswordEntryStep() {
		assertFileContainsAll(
			"src/main/java/org/briarproject/briar/android/login/StartupViewModel.java",
			"private String telegramLoginPassword = \"\";",
			"String getTelegramLoginPassword() {\n\t\treturn telegramLoginPassword;\n\t}",
			"void setTelegramLoginPassword(String password) {\n\t\ttelegramLoginPassword = password;\n\t}",
			"void submitTelegramLoginPassword() {\n\t\ttelegramAuthSession.submitPassword(telegramLoginPassword);\n\t\ttelegramAuthState.setValue(telegramAuthSession.getCurrentState());\n\t\tif (telegramAuthState.getValue() != TelegramAuthState.RECOVERABLE_ERROR ||\n\t\t\t\tgetTelegramRecoverableErrorDetail() != RecoverableErrorDetail.INVALID_PASSWORD) {\n\t\t\ttelegramLoginPassword = \"\";\n\t\t}\n\t}",
		)
		assertTelegramLoginPlaceholderFragmentContainsAll(
			"TELEGRAM_LOGIN_PASSWORD_STEP_TAG",
			"TELEGRAM_LOGIN_PASSWORD_TAG",
			"PasswordStep(",
			"viewModel.setTelegramLoginPassword(it)",
			"viewModel::submitTelegramLoginPassword",
		)
		assertFileMissing("src/main/res/layout/fragment_telegram_login_placeholder.xml")
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramLoginPlaceholderStagesConfirmationStep() {
		assertStartupViewModelContainsAll(
			"private final MutableLiveData<TelegramAuthState> telegramAuthState =\n\t\t\tnew MutableLiveData<>(TelegramAuthState.CLOSED);",
			"LiveData<TelegramAuthState> getTelegramAuthState() {\n\t\treturn telegramAuthState;\n\t}",
			"RecoverableErrorDetail getTelegramRecoverableErrorDetail() {\n\t\treturn telegramAuthSession.getRecoverableErrorDetail();\n\t}",
			"void submitTelegramLoginIdentifier() {\n\t\ttelegramLoginCode = telegramLoginPassword = \"\";\n\t\ttelegramAuthSession.submitIdentifier(telegramLoginIdentifier.trim());\n\t\ttelegramAuthState.setValue(telegramAuthSession.getCurrentState());\n\t}",
			"void showTelegramLoginIdentifierStep() {\n\t\ttelegramLoginCode = telegramLoginPassword = \"\";\n\t\ttelegramAuthSession.close();\n\t\ttelegramAuthSession.start();\n\t\ttelegramAuthState.setValue(telegramAuthSession.getCurrentState());\n\t}",
			"boolean isShowingTelegramLoginConfirmation() {\n\t\tTelegramAuthState authState = telegramAuthState.getValue();\n\t\treturn authState == TelegramAuthState.CODE_ENTRY ||\n\t\t\t\tauthState == TelegramAuthState.PASSWORD_ENTRY ||\n\t\t\t\tauthState == TelegramAuthState.READY ||\n\t\t\t\tauthState == TelegramAuthState.RECOVERABLE_ERROR &&\n\t\t\t\t\t\t(getTelegramRecoverableErrorDetail() == RecoverableErrorDetail.INVALID_CODE ||\n\t\t\t\t\t\t\t\tgetTelegramRecoverableErrorDetail() == RecoverableErrorDetail.INVALID_PASSWORD);\n\t}",
		)
		assertStartupActivityContainsAll(
			"if (viewModel.isShowingTelegramLoginConfirmation()) {\n\t\t\t\tviewModel.showTelegramLoginIdentifierStep();\n\t\t\t\treturn;\n\t\t\t}",
		)
		assertTelegramLoginPlaceholderFragmentContainsAll(
			"TELEGRAM_LOGIN_CONFIRMATION_TAG",
			"TELEGRAM_LOGIN_CONFIRMATION_BACK_TAG",
			"viewModel::completeTelegramLoginConfirmation",
			"viewModel::showTelegramLoginIdentifierStep",
			"authState == TelegramAuthState.PASSWORD_ENTRY ||",
			"errorDetail == RecoverableErrorDetail.INVALID_PASSWORD",
			"authState == TelegramAuthState.READY",
			"R.string.telegram_connector_login_tdlib_missing_message",
			"R.string.telegram_connector_login_identifier_invalid_message",
			"R.string.telegram_connector_login_password_invalid_message",
			"R.string.telegram_connector_login_code_invalid_message",
			"R.string.telegram_connector_login_confirmation_message",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramLoginCompletionStagesLinkedIdentityAfterPasswordSignIn() {
		assertStartupViewModelContainsAll(
			"void completeTelegramLoginConfirmation() {\n\t\tpendingTelegramLinkedIdentity = telegramLoginIdentifier.trim();\n\t\tshowPasswordFragment();\n\t}",
			"accountManager.signIn(password);\n\t\t\t\tstorePendingTelegramLinkedIdentity();\n\t\t\t\tpasswordValidated.postEvent(SUCCESS);",
			"private void storePendingTelegramLinkedIdentity() {\n\t\tif (pendingTelegramLinkedIdentity.isEmpty()) return;",
			"settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testStartupActivityShowsTelegramIdentityHandoffConfirmation() {
		assertStartupViewModelContainsAll(
			"LiveEvent<String> getTelegramLinkedIdentityStaged() {\n\t\treturn telegramLinkedIdentityStaged;\n\t}",
			"telegramLinkedIdentityStaged.postEvent(lastTelegramLinkedIdentityStaged);",
		)
		assertStartupActivityContainsAll(
			"viewModel.getTelegramLinkedIdentityStaged().observeEvent(this,\n\t\t\t\tidentifier -> {\n\t\t\t\t\tstagedTelegramLoginIdentity = identifier;\n\t\t\t\t\tToast.makeText(this,\n\t\t\t\t\t\t\tgetString(\n\t\t\t\t\t\t\t\t\tR.string.telegram_connector_login_handoff_staged,\n\t\t\t\t\t\t\t\t\tidentifier),\n\t\t\t\t\t\t\tLENGTH_LONG).show();\n\t\t\t\t});",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testStartupActivityPersistsTelegramIdentityHandoffAcrossRecreation() {
		assertStartupActivityContainsAll(
			"private static final String KEY_STAGED_TELEGRAM_LOGIN_IDENTITY =\n\t\t\t\"stagedTelegramLoginIdentity\";",
			"if (state != null) {\n\t\t\tstagedTelegramLoginIdentity = state.getString(\n\t\t\t\t\tKEY_STAGED_TELEGRAM_LOGIN_IDENTITY, \"\");\n\t\t}",
			"protected void onSaveInstanceState(Bundle state) {\n\t\tsuper.onSaveInstanceState(state);\n\t\tstate.putString(KEY_STAGED_TELEGRAM_LOGIN_IDENTITY,\n\t\t\t\tstagedTelegramLoginIdentity);\n\t}",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testStartupLoginCanOfferTelegramSetupEntrypointAfterFreshHandoff() {
		assertStartupActivityContainsAll(
			"public static final String EXTRA_STAGED_TELEGRAM_LOGIN_IDENTITY =\n\t\t\t\"briar.STAGED_TELEGRAM_LOGIN_IDENTITY\";",
			"if (stagedTelegramLoginIdentity.isEmpty()) {\n\t\t\t\tstagedTelegramLoginIdentity =\n\t\t\t\t\t\tviewModel.getLastTelegramLinkedIdentityStaged();\n\t\t\t}",
			"result.putExtra(EXTRA_STAGED_TELEGRAM_LOGIN_IDENTITY,\n\t\t\t\t\t\tstagedTelegramLoginIdentity);",
		)
		assertBriarActivityContainsAll(
			"private static final String EXTRA_PENDING_TELEGRAM_LOGIN_ENTRYPOINT =\n\t\t\t\"briar.PENDING_TELEGRAM_LOGIN_ENTRYPOINT\";",
			"String stagedIdentity = data.getStringExtra(\n\t\t\t\t\t\tStartupActivity.EXTRA_STAGED_TELEGRAM_LOGIN_IDENTITY);",
			"getIntent().putExtra(EXTRA_PENDING_TELEGRAM_LOGIN_ENTRYPOINT,\n\t\t\t\t\t\t\tstagedIdentity);",
			"maybeShowTelegramLoginSetupEntryPoint();",
			"private void maybeShowTelegramLoginSetupEntryPoint() {",
			"getIntent().removeExtra(EXTRA_PENDING_TELEGRAM_LOGIN_ENTRYPOINT);",
			"R.string.telegram_connector_login_entrypoint_message,\n\t\t\t\t\t\tlinkedIdentity))",
			"Intent i = new Intent(this, SettingsActivity.class);",
			"i.setAction(ACTION_MANAGE_NETWORK_USAGE);",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testPostSignInTelegramSetupEntrypointCanAutoOpenPlaceholder() {
		assertFileContains(
			"src/main/kotlin/org/briarproject/briar/android/settings/SettingsActivity.kt",
			"const val EXTRA_OPEN_TELEGRAM_SETUP = \"openTelegramSetup\"",
		)
		assertFileContains(
			"src/main/kotlin/org/briarproject/briar/android/settings/SettingsActivity.kt",
			"fun consumeOpenTelegramSetup(): Boolean {\n\t\tval openTelegramSetup = intent.getBooleanExtra(\n\t\t\t\tEXTRA_OPEN_TELEGRAM_SETUP, false\n\t\t)\n\t\tintent.removeExtra(EXTRA_OPEN_TELEGRAM_SETUP)\n\t\treturn openTelegramSetup\n\t}",
		)
		assertBriarActivityContainsAll(
			"i.putExtra(SettingsActivity.EXTRA_OPEN_TELEGRAM_SETUP, true);",
		)
		assertConnectionsFragmentContainsAll(
			"if (requireSettingsActivity().consumeOpenTelegramSetup()) {",
			"showTelegramSetupDialog(requireSettingsActivity()",
			".isTelegramConnectorReady(), value);",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramIdentityReviewCanContinueToVerificationPlaceholder() {
		assertConnectionsFragmentContainsAll(
			"static final String PREF_KEY_TELEGRAM_VERIFICATION = \"pref_key_telegram_verification\";",
			"private Preference telegramVerification;",
			"telegramVerification = findPreference(PREF_KEY_TELEGRAM_VERIFICATION);",
			"telegramVerification.setVisible(telegramConnector.isEnabled());",
			"telegramVerification.setOnPreferenceClickListener(preference -> {",
			"updateTelegramVerificationState(telegramLinkedIdentity.getText());",
			"showTelegramVerificationDialog(telegramLinkedIdentity.getText());",
			"telegramVerification.setEnabled(requireSettingsActivity().isTelegramConnectorReady()\n\t\t\t\t\t\t\t&& !isNullOrEmpty(value));",
			"private void updateTelegramVerificationState(@Nullable String linkedIdentity) {",
			"private void showTelegramVerificationDialog(@Nullable String linkedIdentity) {",
		)
		assertFileContains(
			"src/main/res/xml/settings_connections.xml",
			"android:key=\"pref_key_telegram_verification\"",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramAuthPlaceholderCompletionUpdatesVerificationSummary() {
		assertConnectionsFragmentContainsAll(
			"private String telegramAuthenticationPlaceholderCompletedIdentity;",
			"telegramAuthenticationPlaceholderCompletedIdentity = linkedIdentity;",
			"updateTelegramVerificationState(linkedIdentity);",
			"linkedIdentity.equals(telegramAuthenticationPlaceholderCompletedIdentity)",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testTelegramVerificationCompletionResetsWhenIdentityChanges() {
		assertConnectionsFragmentContainsAll(
			"clearTelegramVerificationCompletionIfIdentityChanged(value);",
			"private void clearTelegramVerificationCompletionIfIdentityChanged(\n\t\t\t@Nullable String linkedIdentity) {",
			"if (isNullOrEmpty(telegramAuthenticationPlaceholderCompletedIdentity)) return;",
			"if (!telegramAuthenticationPlaceholderCompletedIdentity.equals(linkedIdentity)) {",
			"telegramAuthenticationPlaceholderCompletedIdentity = null;",
		)
	}

	@Test
	@Throws(IOException::class)
	fun testConnectionsSettingsExposeTelegramIdentityLinkingSeam() {
		assertConnectionsFragmentContainsAll(
			"static final String PREF_KEY_TELEGRAM_LINKED_IDENTITY =\n\t\t\t\"pref_key_telegram_linked_identity\";",
			"telegramLinkedIdentity.setPreferenceDataStore(viewModel.settingsStore);",
			"telegramLinkedIdentity.setSummaryProvider(preference -> {",
			"viewModel.getTelegramLinkedIdentity().observe(lifecycleOwner,\n\t\t\t\tvalue -> {",
			"telegramLinkedIdentity.setText(value);",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/settings/SettingsViewModel.java",
			"private final MutableLiveData<String> telegramLinkedIdentity =\n\t\t\tnew MutableLiveData<>();",
		)
		assertFileContains(
			"src/main/java/org/briarproject/briar/android/settings/SettingsViewModel.java",
			"telegramLinkedIdentity.postValue(settings.get(\n\t\t\t\tConnectionsFragment.PREF_KEY_TELEGRAM_LINKED_IDENTITY));",
		)
		assertFileContains(
			"src/main/res/xml/settings_connections.xml",
			"android:key=\"pref_key_telegram_linked_identity\"",
		)
	}

	private fun assertFileContains(
		moduleRelativePath: String,
		expectedText: String,
	) {
		val contents = String(
			Files.readAllBytes(resolveModulePath(moduleRelativePath)),
			StandardCharsets.UTF_8,
		)
		assertTrue(
			"Expected to find '$expectedText' in $moduleRelativePath",
			contents.contains(expectedText),
		)
	}

	private fun assertFileMissing(moduleRelativePath: String) {
		val cwd = Paths.get("").toAbsolutePath().normalize()
		val direct = cwd.resolve(moduleRelativePath).normalize()
		val nested = cwd.resolve("briar-android").resolve(moduleRelativePath)
			.normalize()
		assertTrue(
			"Expected file to be absent: $moduleRelativePath",
			!Files.exists(direct) && !Files.exists(nested),
		)
	}

	private fun assertFileContainsAll(
		moduleRelativePath: String,
		vararg expectedTexts: String,
	) {
		for (expectedText in expectedTexts) {
			assertFileContains(moduleRelativePath, expectedText)
		}
	}

	private fun assertTelegramSubtitleConsumer(moduleRelativePath: String) {
		val contents = String(
			Files.readAllBytes(resolveModulePath(moduleRelativePath)),
			StandardCharsets.UTF_8,
		)
		val hasJavaOverride = contents.contains(
			"protected void onTelegramLinkedIdentityAvailable(\n\t\t\t@Nullable String linkedIdentity) {",
		)
		val hasKotlinOverride = contents.contains(
			"override fun onTelegramLinkedIdentityAvailable(",
		)
		assertTrue(
			"Expected Telegram subtitle override in $moduleRelativePath",
			hasJavaOverride || hasKotlinOverride,
		)
		assertTrue(
			"Expected Telegram subtitle consumer body in $moduleRelativePath",
			contents.contains("showTelegramLinkedIdentitySubtitle(linkedIdentity)"),
		)
	}

	private fun assertBriarActivityContainsAll(vararg expectedTexts: String) {
		assertFileContainsAll(
			"src/main/java/org/briarproject/briar/android/activity/BriarActivity.java",
			*expectedTexts,
		)
	}

	private fun assertConnectionsFragmentContainsAll(
		vararg expectedTexts: String,
	) {
		assertFileContainsAll(
			"src/main/java/org/briarproject/briar/android/settings/ConnectionsFragment.java",
			*expectedTexts,
		)
	}

	private fun assertStartupActivityContainsAll(vararg expectedTexts: String) {
		assertFileContainsAll(
			"src/main/java/org/briarproject/briar/android/login/StartupActivity.java",
			*expectedTexts,
		)
	}

	private fun assertStartupViewModelContainsAll(vararg expectedTexts: String) {
		assertFileContainsAll(
			"src/main/java/org/briarproject/briar/android/login/StartupViewModel.java",
			*expectedTexts,
		)
	}

	private fun assertTelegramLoginPlaceholderFragmentContainsAll(
		vararg expectedTexts: String,
	) {
		assertFileContainsAll(
			"src/main/kotlin/org/briarproject/briar/android/login/TelegramLoginPlaceholderFragment.kt",
			*expectedTexts,
		)
	}

	private fun resolveModulePath(moduleRelativePath: String): Path {
		val cwd = Paths.get("").toAbsolutePath().normalize()
		val direct = cwd.resolve(moduleRelativePath).normalize()
		if (Files.exists(direct)) return direct
		val nested = cwd.resolve("briar-android").resolve(moduleRelativePath)
			.normalize()
		if (Files.exists(nested)) return nested
		throw IllegalStateException(
			"Could not resolve module path: $moduleRelativePath from $cwd",
		)
	}
}
