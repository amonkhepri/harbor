package org.briarproject.briar.android.contact;

import android.app.Application;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.event.PendingContactAddedEvent;
import org.briarproject.bramble.api.contact.event.PendingContactRemovedEvent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.telegram.TelegramChat;
import org.briarproject.briar.api.telegram.TelegramConnector;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import org.briarproject.bramble.api.lifecycle.IoExecutor;

@NotNullByDefault
class ContactListViewModel extends ContactsViewModel {

	private final AndroidNotificationManager notificationManager;
	private final TelegramConnector telegramConnector;
	@IoExecutor
	private final Executor ioExecutor;

	private final MutableLiveData<Boolean> hasPendingContacts =
			new MutableLiveData<>();
	private final MutableLiveData<List<TelegramInboxThreadItem>>
			telegramThreadItems = new MutableLiveData<>(Collections.emptyList());
	private final MutableLiveData<TelegramInboxAvailabilityState>
			telegramAvailabilityState =
			new MutableLiveData<>(TelegramInboxAvailabilityState.NONE);

	@Inject
	ContactListViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, ContactManager contactManager,
			AuthorManager authorManager,
			ConversationManager conversationManager,
			ConnectionRegistry connectionRegistry, EventBus eventBus,
			AndroidNotificationManager notificationManager,
			TelegramConnector telegramConnector,
			@IoExecutor Executor ioExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor,
				contactManager, authorManager, conversationManager,
				connectionRegistry, eventBus);
		this.notificationManager = notificationManager;
		this.telegramConnector = telegramConnector;
		this.ioExecutor = ioExecutor;
	}

	@Override
	public void eventOccurred(Event e) {
		super.eventOccurred(e);
		if (e instanceof PendingContactAddedEvent ||
				e instanceof PendingContactRemovedEvent) {
			checkForPendingContacts();
		}
	}

	LiveData<Boolean> getHasPendingContacts() {
		return hasPendingContacts;
	}

	LiveData<List<TelegramInboxThreadItem>> getTelegramThreadItems() {
		return telegramThreadItems;
	}

	LiveData<TelegramInboxAvailabilityState> getTelegramAvailabilityState() {
		return telegramAvailabilityState;
	}

	boolean isTelegramConnectorEnabled() {
		return telegramConnector.isEnabled();
	}

	void checkForPendingContacts() {
		runOnDbThread(() -> {
			try {
				boolean hasPending =
						!contactManager.getPendingContacts().isEmpty();
				hasPendingContacts.postValue(hasPending);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void clearAllContactNotifications() {
		notificationManager.clearAllContactNotifications();
	}

	void clearAllContactAddedNotifications() {
		notificationManager.clearAllContactAddedNotifications();
	}

	void loadTelegramThreads() {
		if (!telegramConnector.isEnabled()) {
			telegramAvailabilityState.setValue(
					TelegramInboxAvailabilityState.NONE);
			telegramThreadItems.setValue(Collections.emptyList());
			return;
		}
		telegramAvailabilityState.setValue(TelegramInboxAvailabilityState.LOADING);
		ioExecutor.execute(() -> {
			try {
				if (!telegramConnector.isAuthorized()) {
					telegramAvailabilityState.postValue(
							telegramAvailabilityStateFor(true, false, false,
									false));
					telegramThreadItems.postValue(Collections.emptyList());
					return;
				}
				List<TelegramChat> chats = telegramConnector.getRecentChats(20);
				telegramAvailabilityState.postValue(
						telegramAvailabilityStateFor(true, true, false,
								!chats.isEmpty()));
				List<TelegramInboxThreadItem> items =
						new ArrayList<>(chats.size());
				for (TelegramChat chat : chats) {
					items.add(new TelegramInboxThreadItem(chat.getId(),
							chat.getTitle(),
							chat.getLastMessageDateSeconds() * 1000L));
				}
				telegramThreadItems.postValue(items);
				List<TelegramInboxThreadItem> previewItems =
						new ArrayList<>(chats.size());
				for (TelegramChat chat : chats) {
					previewItems.add(new TelegramInboxThreadItem(chat));
				}
				telegramThreadItems.postValue(previewItems);
			} catch (RuntimeException e) {
				telegramAvailabilityState.postValue(
						telegramAvailabilityStateFor(true, true, true,
								false));
				telegramThreadItems.postValue(Collections.emptyList());
			}
		});
	}

	static TelegramInboxAvailabilityState telegramAvailabilityStateFor(
			boolean connectorEnabled, boolean authorized, boolean loadFailed,
			boolean hasRows) {
		if (!connectorEnabled || hasRows) {
			return TelegramInboxAvailabilityState.NONE;
		} else if (loadFailed) {
			return TelegramInboxAvailabilityState.LOAD_FAILED;
		} else if (!authorized) {
			return TelegramInboxAvailabilityState.ACCOUNT_UNAVAILABLE;
		}
		return TelegramInboxAvailabilityState.EMPTY;
	}

}
