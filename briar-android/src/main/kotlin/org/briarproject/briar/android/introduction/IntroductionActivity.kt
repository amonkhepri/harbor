package org.briarproject.briar.android.introduction

import android.content.Intent
import android.os.Bundle
import androidx.annotation.NonNull
import androidx.lifecycle.ViewModelProvider
import org.briarproject.bramble.api.contact.ContactId
import org.briarproject.briar.R
import org.briarproject.briar.android.activity.ActivityComponent
import org.briarproject.briar.android.activity.BriarActivity
import org.briarproject.briar.android.conversation.ConversationActivity.CONTACT_ID
import org.briarproject.briar.android.fragment.BaseFragment.BaseFragmentListener
import org.briarproject.nullsafety.MethodsNotNullByDefault
import org.briarproject.nullsafety.ParametersNotNullByDefault
import javax.inject.Inject

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class IntroductionActivity :
	BriarActivity(),
	BaseFragmentListener {

	companion object {
		private const val BUNDLE_CONTACT2 = "contact2"

		// Bundle.getInt() defaults to 0 when the key is absent, which is
		// indistinguishable from a real ContactId. onSaveInstanceState only
		// writes BUNDLE_CONTACT2 once a second contact has been selected, so
		// restoring must check for its presence rather than trusting the
		// default: a savedInstanceState from a rotation on ContactChooserFragment
		// (before any contact was picked) never contains the key and must not
		// be misread as ContactId(0).
		internal fun restoredSecondContactId(savedInstanceState: Bundle): Int? =
			if (savedInstanceState.containsKey(BUNDLE_CONTACT2)) {
				savedInstanceState.getInt(BUNDLE_CONTACT2)
			} else {
				null
			}
	}

	@Inject
	lateinit var viewModelFactory: ViewModelProvider.Factory

	private lateinit var viewModel: IntroductionViewModel

	override fun injectActivity(component: ActivityComponent) {
		component.inject(this)
		viewModel = ViewModelProvider(this, viewModelFactory)
			.get(IntroductionViewModel::class.java)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val launchIntent: Intent = intent
		val contactId1 = launchIntent.getIntExtra(CONTACT_ID, -1)
		if (contactId1 == -1) throw IllegalStateException("No ContactId")
		viewModel.setFirstContactId(ContactId(contactId1))

		setContentView(R.layout.activity_fragment_container)

		if (savedInstanceState == null) {
			showInitialFragment(ContactChooserFragment())
		} else {
			restoredSecondContactId(savedInstanceState)?.let {
				viewModel.setSecondContactId(ContactId(it))
			}
		}

		viewModel.secondContactSelected.observeEvent(this) {
			showNextFragment(IntroductionMessageFragment())
		}
	}

	override fun onSaveInstanceState(@NonNull outState: Bundle) {
		super.onSaveInstanceState(outState)

		val secondContactId = viewModel.secondContactId
		if (secondContactId != null) {
			outState.putInt(BUNDLE_CONTACT2, secondContactId.getInt())
		}
	}
}
