package org.briarproject.briar.android.login;

import android.app.Application;

import org.briarproject.bramble.PoliteExecutor;
import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.crypto.DecryptionException;
import org.briarproject.bramble.api.crypto.DecryptionResult;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState;
import org.briarproject.bramble.api.lifecycle.event.LifecycleEvent;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.telegram.TelegramAuthSession;
import org.briarproject.briar.api.telegram.TelegramAuthSession.RecoverableErrorDetail;
import org.briarproject.briar.api.telegram.TelegramAuthSession.Snapshot;
import org.briarproject.briar.api.telegram.TelegramAuthState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.briarproject.bramble.api.crypto.DecryptionResult.SUCCESS;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.COMPACTING_DATABASE;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.MIGRATING_DATABASE;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.STARTING_SERVICES;
import static org.briarproject.briar.android.login.StartupViewModel.State.COMPACTING;
import static org.briarproject.briar.android.login.StartupViewModel.State.MIGRATING;
import static org.briarproject.briar.android.login.StartupViewModel.State.SIGNED_IN;
import static org.briarproject.briar.android.login.StartupViewModel.State.SIGNED_OUT;
import static org.briarproject.briar.android.login.StartupViewModel.State.STARTED;
import static org.briarproject.briar.android.login.StartupViewModel.State.STARTING;
import static org.briarproject.briar.android.login.StartupViewModel.State.TELEGRAM_LOGIN;

@NotNullByDefault
public class StartupViewModel extends AndroidViewModel
		implements EventListener {

	enum State {SIGNED_OUT, TELEGRAM_LOGIN, SIGNED_IN, STARTING, MIGRATING, COMPACTING, STARTED}

	private final AccountManager accountManager;
	private final AndroidNotificationManager notificationManager;
	private final EventBus eventBus;
	private final FeatureFlags featureFlags;
	private final TelegramAuthSession telegramAuthSession;
	@IoExecutor
	private final Executor ioExecutor;
	private final Executor telegramAuthExecutor;
	private final AtomicBoolean passwordValidationInProgress =
			new AtomicBoolean();
	private final AtomicBoolean accountDeletionInProgress =
			new AtomicBoolean();
	private final AtomicInteger telegramAuthGeneration = new AtomicInteger();

	private final MutableLiveEvent<DecryptionResult> passwordValidated =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> accountDeleted =
			new MutableLiveEvent<>();
	private final MutableLiveData<State> state = new MutableLiveData<>();
	private final MutableLiveData<Snapshot> telegramAuthSnapshot =
			new MutableLiveData<>(new Snapshot(
					TelegramAuthState.CLOSED, RecoverableErrorDetail.NONE));
	private String telegramLoginIdentifier = "";
	private String telegramLoginCode = "";
	private String telegramLoginPassword = "";

	@Inject
	StartupViewModel(Application app,
			AccountManager accountManager,
			LifecycleManager lifecycleManager,
			AndroidNotificationManager notificationManager,
			EventBus eventBus,
			@IoExecutor Executor ioExecutor,
			FeatureFlags featureFlags,
			TelegramAuthSession telegramAuthSession) {
		super(app);
		this.accountManager = accountManager;
		this.notificationManager = notificationManager;
		this.eventBus = eventBus;
		this.ioExecutor = ioExecutor;
		telegramAuthExecutor = new PoliteExecutor("TelegramAuth", ioExecutor, 1);
		this.featureFlags = featureFlags;
		this.telegramAuthSession = telegramAuthSession;

		updateState(lifecycleManager.getLifecycleState());
		Snapshot activeTelegramAuth = telegramAuthSession.getSnapshot();
		if (isResumableQrSession(activeTelegramAuth)) {
			telegramAuthSnapshot.setValue(activeTelegramAuth);
			state.setValue(TELEGRAM_LOGIN);
		}
		eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		eventBus.removeListener(this);
		telegramAuthGeneration.incrementAndGet();
		if (isResumableQrSession(telegramAuthSession.getSnapshot())) return;
		telegramAuthExecutor.execute(telegramAuthSession::close);
	}

	private static boolean isResumableQrSession(Snapshot snapshot) {
		return snapshot.getAuthState() == TelegramAuthState.QR_WAITING ||
				snapshot.getQrAuthorizationLink() != null;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof LifecycleEvent) {
			LifecycleState s = ((LifecycleEvent) e).getLifecycleState();
			updateState(s);
		}
	}

	@UiThread
	private void updateState(LifecycleState s) {
		if (accountManager.hasDatabaseKey()) {
			if (s.isAfter(STARTING_SERVICES)) state.setValue(STARTED);
			else if (s == MIGRATING_DATABASE) state.setValue(MIGRATING);
			else if (s == COMPACTING_DATABASE) state.setValue(COMPACTING);
			else state.setValue(STARTING);
		} else {
			if (state.getValue() != TELEGRAM_LOGIN) state.setValue(SIGNED_OUT);
		}
	}

	boolean accountExists() {
		return accountManager.accountExists();
	}

	void clearSignInNotification() {
		notificationManager.blockSignInNotification();
		notificationManager.clearSignInNotification();
	}

	void validatePassword(String password) {
		if (accountDeletionInProgress.get()) return;
		passwordValidationInProgress.set(true);
		ioExecutor.execute(() -> {
			try {
				accountManager.signIn(password);
				passwordValidated.postEvent(SUCCESS);
				state.postValue(SIGNED_IN);
			} catch (DecryptionException e) {
				passwordValidated.postEvent(e.getDecryptionResult());
			} finally {
				passwordValidationInProgress.set(false);
			}
		});
	}

	boolean isPasswordValidationInProgress() {
		return passwordValidationInProgress.get();
	}

	boolean isAccountDeletionInProgress() {
		return accountDeletionInProgress.get();
	}

	LiveEvent<DecryptionResult> getPasswordValidated() {
		return passwordValidated;
	}

	LiveEvent<Boolean> getAccountDeleted() {
		return accountDeleted;
	}

	LiveData<State> getState() {
		return state;
	}

	void showTelegramLoginPlaceholder() {
		if (passwordValidationInProgress.get()) return;
		telegramAuthGeneration.incrementAndGet();
		telegramLoginCode = telegramLoginPassword = "";
		state.setValue(TELEGRAM_LOGIN);
		telegramAuthSnapshot.setValue(new Snapshot(
				TelegramAuthState.CLOSED, RecoverableErrorDetail.NONE));
		runTelegramAuthAction(telegramAuthSession::start);
	}

	String getTelegramLoginIdentifier() {
		return telegramLoginIdentifier;
	}

	void setTelegramLoginIdentifier(String identifier) {
		telegramLoginIdentifier = identifier;
	}

	String getTelegramLoginCode() {
		return telegramLoginCode;
	}

	void setTelegramLoginCode(String code) {
		telegramLoginCode = code;
	}

	String getTelegramLoginPassword() {
		return telegramLoginPassword;
	}

	void setTelegramLoginPassword(String password) {
		telegramLoginPassword = password;
	}

	LiveData<Snapshot> getTelegramAuthSnapshot() {
		return telegramAuthSnapshot;
	}

	void submitTelegramLoginIdentifier() {
		telegramLoginCode = telegramLoginPassword = "";
		String identifier = telegramLoginIdentifier.trim();
		runTelegramAuthAction(() -> telegramAuthSession.submitIdentifier(identifier));
	}

	void requestTelegramQrAuthorization() {
		telegramLoginCode = telegramLoginPassword = "";
		runTelegramAuthAction(telegramAuthSession::requestQrCodeAuthentication);
	}

	void awaitTelegramQrAuthorization() {
		int generation = telegramAuthGeneration.get();
		telegramAuthExecutor.execute(() -> {
			if (generation != telegramAuthGeneration.get()) return;
			while (generation == telegramAuthGeneration.get()) {
				telegramAuthSession.awaitQrAuthorizationUpdate();
				if (generation != telegramAuthGeneration.get()) return;
				Snapshot snapshot = telegramAuthSession.getSnapshot();
				telegramAuthSnapshot.postValue(snapshot);
				if (snapshot.getAuthState() != TelegramAuthState.QR_WAITING) return;
			}
		});
	}

	void submitTelegramLoginCode() {
		String code = telegramLoginCode.trim();
		runTelegramAuthAction(() -> {
			telegramAuthSession.submitCode(code);
			Snapshot snapshot = telegramAuthSession.getSnapshot();
			if (snapshot.getAuthState() != TelegramAuthState.RECOVERABLE_ERROR ||
					snapshot.getErrorDetail() != RecoverableErrorDetail.INVALID_CODE) {
				telegramLoginCode = "";
			}
		});
	}

	void resendTelegramLoginCode() {
		telegramLoginCode = "";
		runTelegramAuthAction(telegramAuthSession::resendCode);
	}

	void submitTelegramLoginPassword() {
		String password = telegramLoginPassword;
		runTelegramAuthAction(() -> {
			telegramAuthSession.submitPassword(password);
			Snapshot snapshot = telegramAuthSession.getSnapshot();
			if (snapshot.getAuthState() != TelegramAuthState.RECOVERABLE_ERROR ||
					snapshot.getErrorDetail() != RecoverableErrorDetail.INVALID_PASSWORD) {
				telegramLoginPassword = "";
			}
		});
	}

	void completeTelegramLoginConfirmation() {
		showPasswordFragment();
	}

	void showTelegramLoginIdentifierStep() {
		telegramAuthGeneration.incrementAndGet();
		telegramLoginCode = telegramLoginPassword = "";
		telegramAuthSnapshot.setValue(new Snapshot(
				TelegramAuthState.CLOSED, RecoverableErrorDetail.NONE));
		runTelegramAuthAction(() -> {
			telegramAuthSession.close();
			telegramAuthSession.start();
		});
	}

	boolean isShowingTelegramLoginConfirmation() {
		Snapshot snapshot = telegramAuthSnapshot.getValue();
		TelegramAuthState authState = snapshot.getAuthState();
		return authState == TelegramAuthState.CODE_ENTRY ||
				authState == TelegramAuthState.PASSWORD_ENTRY ||
				authState == TelegramAuthState.QR_WAITING ||
				authState == TelegramAuthState.READY ||
				authState == TelegramAuthState.RECOVERABLE_ERROR &&
						(snapshot.getErrorDetail() == RecoverableErrorDetail.INVALID_CODE ||
								snapshot.getErrorDetail() == RecoverableErrorDetail.CODE_RESEND_FAILED ||
								snapshot.getErrorDetail() == RecoverableErrorDetail.INVALID_PASSWORD);
	}

	void showPasswordFragment() {
		telegramAuthGeneration.incrementAndGet();
		telegramLoginIdentifier = telegramLoginCode = telegramLoginPassword = "";
		state.setValue(SIGNED_OUT);
		runTelegramAuthAction(telegramAuthSession::close);
	}

	private void runTelegramAuthAction(Runnable action) {
		int generation = telegramAuthGeneration.get();
		telegramAuthExecutor.execute(() -> {
			if (generation != telegramAuthGeneration.get()) return;
			action.run();
			if (generation != telegramAuthGeneration.get()) return;
			Snapshot snapshot = telegramAuthSession.getSnapshot();
			telegramAuthSnapshot.postValue(snapshot);
		});
	}

	boolean shouldShowTelegramLogin() {
		return featureFlags.shouldEnableTelegramConnector();
	}

	@UiThread
	void deleteAccount() {
		if (passwordValidationInProgress.get()) return;
		if (!accountDeletionInProgress.compareAndSet(false, true)) return;
		telegramAuthExecutor.execute(() -> {
			telegramAuthSession.close();
			accountManager.deleteAccount();
			accountDeleted.postEvent(true);
		});
	}

}
