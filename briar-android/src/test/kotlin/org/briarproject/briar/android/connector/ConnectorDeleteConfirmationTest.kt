package org.briarproject.briar.android.connector

import android.app.Application
import android.os.Parcel
import androidx.fragment.app.FragmentFactory
import org.briarproject.briar.android.Localizer
import org.briarproject.briar.api.connector.ConnectorMessageDeleter
import org.briarproject.briar.api.connector.ReadOnlyConnector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.withSettings
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConnectorDeleteConfirmationTest {
	@Test
	fun restoredTargetDeletesBeforeMessagesReload() {
		val original = ConnectorDeleteMessageDialogFragment.newInstance("room", "message")
		val parcel = Parcel.obtain()
		val restoredArguments = try {
			parcel.writeBundle(original.requireArguments())
			parcel.setDataPosition(0)
			parcel.readBundle(javaClass.classLoader)!!
		} finally {
			parcel.recycle()
		}
		val restored = FragmentFactory().instantiate(
			javaClass.classLoader,
			ConnectorDeleteMessageDialogFragment::class.java.name,
		) as ConnectorDeleteMessageDialogFragment
		restored.arguments = restoredArguments
		assertEquals("room", restored.requireArguments().getString("threadId"))
		assertEquals("message", restored.requireArguments().getString("messageId"))

		Localizer.initialize(RuntimeEnvironment.getApplication().getSharedPreferences("test", 0))
		val activity = Robolectric.buildActivity(ConnectorConversationActivity::class.java).get()
		val connector = mock(
			ReadOnlyConnector::class.java,
			withSettings().extraInterfaces(ConnectorMessageDeleter::class.java),
		)
		ConnectorConversationActivity::class.java.getDeclaredField("connector").apply {
			isAccessible = true
			set(activity, connector)
		}
		var pending: Runnable? = null
		activity.ioExecutor = Executor { pending = it }
		activity.confirmDeleteMessage(
			restored.requireArguments().getString("threadId")!!,
			restored.requireArguments().getString("messageId")!!,
		)
		// The recreated activity still has empty messageState. Finishing skips UI feedback only.
		activity.finish()
		pending!!.run()
		verify(connector as ConnectorMessageDeleter).deleteMessage("room", "message")
	}
}
