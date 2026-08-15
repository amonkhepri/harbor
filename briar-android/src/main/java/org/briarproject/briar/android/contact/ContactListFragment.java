package org.briarproject.briar.android.contact;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.contact.add.nearby.AddNearbyContactActivity;
import org.briarproject.briar.android.contact.add.remote.AddContactActivity;
import org.briarproject.briar.android.contact.add.remote.PendingContactListActivity;
import org.briarproject.briar.android.conversation.ConversationActivity;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.telegram.TelegramConversationActivity;
import org.briarproject.briar.android.util.BriarSnackbarBuilder;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.api.connector.ConnectorSources;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import io.github.kobakei.materialfabspeeddial.FabSpeedDial;
import io.github.kobakei.materialfabspeeddial.FabSpeedDial.OnMenuItemClickListener;

import static com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_INDEFINITE;
import static java.util.Collections.emptyList;
import static org.briarproject.briar.android.conversation.ConversationActivity.CONTACT_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ContactListFragment extends BaseFragment
		implements OnMenuItemClickListener,
		OnInboxThreadClickListener {

	public static final String TAG = ContactListFragment.class.getName();
	private static final long TELEGRAM_REFRESH_INTERVAL_MS = 15_000;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ContactListViewModel viewModel;
	private final InboxThreadAdapter adapter = new InboxThreadAdapter(this);
	private BriarRecyclerView list;
	private FabSpeedDial speedDial;
	private List<ContactListItem> briarItems = emptyList();
	private List<TelegramInboxThreadItem> telegramItems = emptyList();
	private TelegramInboxAvailabilityState telegramAvailabilityState =
			TelegramInboxAvailabilityState.NONE;

	/**
	 * The Snackbar is non-null when shown and null otherwise.
	 * Use {@link #showSnackBar()} and {@link #dismissSnackBar()} to interact.
	 */
	@Nullable
	private Snackbar snackbar = null;

	public static ContactListFragment newInstance() {
		Bundle args = new Bundle();
		ContactListFragment fragment = new ContactListFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ContactListViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.contact_list_button);

		View contentView = inflater.inflate(R.layout.fragment_contact_list,
				container, false);

		speedDial = contentView.findViewById(R.id.speedDial);
		speedDial.addOnMenuItemClickListener(this);

		list = contentView.findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		list.setAdapter(adapter);
		list.setEmptyImage(R.drawable.il_empty_state_contact_list);
		list.setEmptyText(getString(R.string.no_contacts));
		list.setEmptyAction(getString(R.string.no_contacts_action));

		viewModel.getContactListItems()
				.observe(getViewLifecycleOwner(), result -> {
					result.onError(this::handleException).onSuccess(items -> {
						briarItems = items;
						submitInboxItems();
					});
				});
		viewModel.getTelegramThreadItems()
				.observe(getViewLifecycleOwner(), items -> {
					telegramItems = items;
					submitInboxItems();
				});
		viewModel.getTelegramAvailabilityState()
				.observe(getViewLifecycleOwner(), state -> {
					telegramAvailabilityState = state;
					updateEmptyState();
					requireActivity().invalidateOptionsMenu();
				});
		viewModel.getHasPendingContacts()
				.observe(getViewLifecycleOwner(), hasPending -> {
					if (hasPending) showSnackBar();
					else dismissSnackBar();
				});

		return contentView;
	}

	@Override
	public void onBriarItemClick(View view, ContactListItem item) {
		Intent i = new Intent(getActivity(), ConversationActivity.class);
		ContactId contactId = item.getContact().getId();
		i.putExtra(CONTACT_ID, contactId.getInt());
		startActivity(i);
	}

	@Override
	public void onConnectorItemClick(View view, ConnectorInboxThreadItem item) {
		if (!item.getConnectorSource().equals(ConnectorSources.TELEGRAM)) return;
		Intent i = new Intent(getActivity(), TelegramConversationActivity.class);
		i.putExtra(TelegramConversationActivity.CHAT_ID,
				Long.parseLong(item.getConnectorThreadId()));
		i.putExtra(TelegramConversationActivity.CHAT_TITLE, item.getTitle());
		startActivity(i);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.telegram_inbox_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		MenuItem refresh = menu.findItem(R.id.action_refresh_telegram_threads);
		if (refresh != null) {
			refresh.setVisible(shouldShowManualRefreshAction(
					viewModel.isTelegramConnectorEnabled(),
					telegramAvailabilityState));
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (isManualRefreshAction(item.getItemId())) {
			viewModel.loadTelegramThreads();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onMenuItemClick(FloatingActionButton fab, @Nullable TextView v,
			int itemId) {
		if (itemId == R.id.action_add_contact_nearby) {
			Intent intent =
					new Intent(getContext(), AddNearbyContactActivity.class);
			startActivity(intent);
		} else if (itemId == R.id.action_add_contact_remotely) {
			startActivity(new Intent(getContext(), AddContactActivity.class));
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.clearAllContactNotifications();
		viewModel.clearAllContactAddedNotifications();
		viewModel.loadContacts();
		viewModel.loadTelegramThreads();
		viewModel.checkForPendingContacts();
		list.startPeriodicUpdate(TELEGRAM_REFRESH_INTERVAL_MS,
				viewModel::loadTelegramThreads);
	}

	@Override
	public void onStop() {
		super.onStop();
		list.stopPeriodicUpdate();
		dismissSnackBar();
		// Close the speed dial to prevent a crash (#1672)
		speedDial.closeMenu();
	}

	@UiThread
	private void showSnackBar() {
		if (snackbar != null) return;
		View v = requireView();
		int stringRes = R.string.pending_contact_requests_snackbar;
		snackbar = new BriarSnackbarBuilder()
				.setAction(R.string.show, view -> showPendingContactList())
				.make(v, stringRes, LENGTH_INDEFINITE);
		snackbar.show();
	}

	@UiThread
	private void dismissSnackBar() {
		if (snackbar == null) return;
		snackbar.dismiss();
		snackbar = null;
	}

	private void showPendingContactList() {
		Intent i = new Intent(getContext(), PendingContactListActivity.class);
		startActivity(i);
	}

	private void submitInboxItems() {
		updateEmptyState();
		adapter.submitList(InboxThreadMerger.merge(briarItems, telegramItems), list::showData);
	}

	private void updateEmptyState() {
		list.setEmptyText(emptyTextForState(telegramAvailabilityState));
		int actionRes = emptyActionTextForState(telegramAvailabilityState);
		if (actionRes == 0) list.setEmptyAction("");
		else list.setEmptyAction(actionRes);
	}

	static boolean isManualRefreshAction(int itemId) {
		return itemId == R.id.action_refresh_telegram_threads;
	}

	static boolean shouldShowManualRefreshAction(boolean connectorEnabled,
			TelegramInboxAvailabilityState state) {
		return connectorEnabled &&
				state != TelegramInboxAvailabilityState.LOADING;
	}

	static int emptyTextForState(TelegramInboxAvailabilityState state) {
		switch (state) {
			case LOADING:
				return R.string.telegram_inbox_loading;
			case ACCOUNT_UNAVAILABLE:
				return R.string.telegram_inbox_account_unavailable;
			case EMPTY:
				return R.string.telegram_inbox_empty;
			case LOAD_FAILED:
				return R.string.telegram_inbox_load_failed;
			default:
				return R.string.no_contacts;
		}
	}

	static int emptyActionTextForState(TelegramInboxAvailabilityState state) {
		return state == TelegramInboxAvailabilityState.NONE ?
				R.string.no_contacts_action : 0;
	}
}
