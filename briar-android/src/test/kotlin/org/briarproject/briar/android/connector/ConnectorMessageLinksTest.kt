package org.briarproject.briar.android.connector

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.text.Spannable
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.briarproject.briar.android.widget.LinkDialogFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
class ConnectorMessageLinksTest {

	@Test
	fun testHttpAndHttpsUrlsGetSpans() {
		val context = RuntimeEnvironment.getApplication()
		val samples = mapOf(
			"https://example.com" to listOf("https://example.com"),
			"http://example.org/path?arg=1#hash" to listOf("http://example.org/path?arg=1#hash"),
			"HTTP://EXAMPLE.COM/UPPER" to listOf("http://EXAMPLE.COM/UPPER"),
			"HTTPS://SECURE.ORG" to listOf("https://SECURE.ORG"),
			"hTtPs://mixed.case.com/path" to listOf("https://mixed.case.com/path"),
			"Check https://example.com." to listOf("https://example.com"),
			"Visit (https://example.com/test) today!" to listOf("https://example.com/test"),
			"Two links: http://first.org and https://second.org/doc" to
				listOf("http://first.org", "https://second.org/doc"),
		)

		for ((sample, expected) in samples) {
			val textView = TextView(context)
			textView.text = sample
			ConnectorMessageLinks.linkify(textView)

			val spannable = textView.text as? Spannable
			assertNotNull("Text should be Spannable for '$sample'", spannable)
			val spans = spannable!!.getSpans(0, spannable.length, URLSpan::class.java)
			val urls = spans.map { it.url }
			assertEquals("URLs matched for '$sample'", expected, urls)
			assertNotNull("MovementMethod should be set for '$sample'", textView.movementMethod)
		}
	}

	@Test
	fun testDisallowedSchemesAndMatrixTextDoNotGetSpans() {
		val context = RuntimeEnvironment.getApplication()
		val samples = listOf(
			"javascript:alert(1)",
			"intent://scan/#Intent;scheme=zxing;end",
			"file:///etc/passwd",
			"content://media/external/images/media/1",
			"matrix:r/room:example.org",
			"bare matrix: text",
			"!roomid:matrix.org",
			"@alice:example.com",
			"#channel:matrix.org",
			"user@example.com",
			"+1234567890",
			"plain text with no link at all",
		)

		for (sample in samples) {
			val textView = TextView(context)
			textView.text = sample
			ConnectorMessageLinks.linkify(textView)

			val spannable = textView.text as? Spannable
			val spans = spannable?.getSpans(0, spannable.length, URLSpan::class.java) ?: emptyArray()
			assertEquals("No spans expected for '$sample'", 0, spans.size)
			assertNull("MovementMethod should be null for '$sample'", textView.movementMethod)
		}
	}

	@Test
	fun testAllTouchScenarios() {
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		val parent = FrameLayout(activity)
		val textView = TextView(activity)
		parent.addView(textView)
		activity.setContentView(parent)

		var parentClicked = false
		var parentLongClicked = false
		parent.setOnClickListener { parentClicked = true }
		parent.setOnLongClickListener {
			parentLongClicked = true
			true
		}

		var clickedUrl: String? = null
		textView.text = "https://example.com other text"
		ConnectorMessageLinks.linkify(textView) { clickedUrl = it }

		activity.window.decorView.measure(
			View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
		)
		activity.window.decorView.layout(0, 0, 1000, 100)

		// 1. Short tap on link triggers link click
		val downLink = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
		val upLink = MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 10f, 10f, 0)
		parent.dispatchTouchEvent(downLink)
		parent.dispatchTouchEvent(upLink)
		shadowOf(Looper.getMainLooper()).idle()
		assertEquals("https://example.com", clickedUrl)
		assertFalse("Parent click should not fire", parentClicked)
		assertFalse("Parent long click should not fire", parentLongClicked)

		// 2. Long press on link triggers action menu on parent and does NOT click link
		clickedUrl = null
		parentClicked = false
		parentLongClicked = false
		val downLink2 = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
		parent.dispatchTouchEvent(downLink2)
		shadowOf(Looper.getMainLooper()).idleFor(
			Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 100),
		)
		val upLink2 = MotionEvent.obtain(100, 700, MotionEvent.ACTION_UP, 10f, 10f, 0)
		parent.dispatchTouchEvent(upLink2)
		shadowOf(Looper.getMainLooper()).idle()
		assertNull("Link should not be clicked after long press", clickedUrl)
		assertTrue("Parent long click should fire on link long press", parentLongClicked)
		assertFalse("Parent click should not fire", parentClicked)

		// 3. Short tap outside link triggers parent click (e.g. retry failed send)
		clickedUrl = null
		parentClicked = false
		parentLongClicked = false
		val downOutside = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, 800f, 10f, 0)
		val upOutside = MotionEvent.obtain(1000, 1050, MotionEvent.ACTION_UP, 800f, 10f, 0)
		parent.dispatchTouchEvent(downOutside)
		parent.dispatchTouchEvent(upOutside)
		shadowOf(Looper.getMainLooper()).idle()
		assertNull("Link should not be clicked", clickedUrl)
		assertTrue("Parent click should fire", parentClicked)
		assertFalse("Parent long click should not fire", parentLongClicked)

		// 4. Long press outside link triggers parent long click
		clickedUrl = null
		parentClicked = false
		parentLongClicked = false
		val downOutside2 = MotionEvent.obtain(2000, 2000, MotionEvent.ACTION_DOWN, 800f, 10f, 0)
		parent.dispatchTouchEvent(downOutside2)
		shadowOf(Looper.getMainLooper()).idleFor(
			Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 100),
		)
		val upOutside2 = MotionEvent.obtain(2000, 2700, MotionEvent.ACTION_UP, 800f, 10f, 0)
		parent.dispatchTouchEvent(upOutside2)
		shadowOf(Looper.getMainLooper()).idle()
		assertNull("Link should not be clicked", clickedUrl)
		assertTrue("Parent long click should fire", parentLongClicked)

		// 5. Drag/scroll does not click link
		clickedUrl = null
		parentClicked = false
		parentLongClicked = false
		val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
		val downDrag = MotionEvent.obtain(3000, 3000, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
		val moveDrag = MotionEvent.obtain(3000, 3050, MotionEvent.ACTION_MOVE, 10f + slop + 10f, 10f, 0)
		val upDrag = MotionEvent.obtain(3000, 3100, MotionEvent.ACTION_UP, 10f + slop + 10f, 10f, 0)
		parent.dispatchTouchEvent(downDrag)
		parent.dispatchTouchEvent(moveDrag)
		parent.dispatchTouchEvent(upDrag)
		shadowOf(Looper.getMainLooper()).idle()
		assertNull("Link should not be clicked after drag", clickedUrl)
	}

	@Test
	fun testLinkTapShowsLinkDialogFragmentByDefault() {
		val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
		val parent = FrameLayout(activity)
		val textView = TextView(activity)
		parent.addView(textView)
		activity.setContentView(parent)

		textView.text = "https://matrix.org"
		ConnectorMessageLinks.linkify(textView)

		activity.window.decorView.measure(
			View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
		)
		activity.window.decorView.layout(0, 0, 1000, 100)

		val downLink = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
		val upLink = MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 10f, 10f, 0)
		parent.dispatchTouchEvent(downLink)
		parent.dispatchTouchEvent(upLink)
		shadowOf(Looper.getMainLooper()).idle()
		activity.supportFragmentManager.executePendingTransactions()

		val tag = LinkDialogFragment.newInstance("https://matrix.org").uniqueTag
		val dialog = activity.supportFragmentManager.findFragmentByTag(tag)
		assertNotNull("LinkDialogFragment should be shown", dialog)
		assertTrue(dialog is LinkDialogFragment)
	}

	@Test
	fun testVerticalTouchOutsideLineBoundsDoesNotClickLink() {
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		val parent = FrameLayout(activity)
		val textView = TextView(activity)
		textView.setPadding(0, 40, 0, 40)
		parent.addView(textView)
		activity.setContentView(parent)

		var parentClicked = false
		parent.setOnClickListener { parentClicked = true }

		var clickedUrl: String? = null
		textView.text = "https://example.com"
		ConnectorMessageLinks.linkify(textView) { clickedUrl = it }

		activity.window.decorView.measure(
			View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
		)
		activity.window.decorView.layout(0, 0, 1000, 200)

		// Tap in top padding (y = 10, while padding is 40)
		val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
		val up = MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 10f, 10f, 0)
		parent.dispatchTouchEvent(down)
		parent.dispatchTouchEvent(up)
		shadowOf(Looper.getMainLooper()).idle()

		assertNull("Link should not be clicked when tapping vertical padding", clickedUrl)
		assertTrue("Parent click should fire for touch in padding", parentClicked)
	}

	@Test
	fun testDetachedViewDuringPendingLongPressDoesNotTriggerAction() {
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		val parent = FrameLayout(activity)
		val textView = TextView(activity)
		parent.addView(textView)
		activity.setContentView(parent)

		var parentLongClicked = false
		parent.setOnLongClickListener {
			parentLongClicked = true
			true
		}

		var clickedUrl: String? = null
		textView.text = "https://example.com"
		ConnectorMessageLinks.linkify(textView) { clickedUrl = it }

		activity.window.decorView.measure(
			View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
		)
		activity.window.decorView.layout(0, 0, 1000, 100)

		val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
		parent.dispatchTouchEvent(down)

		// Detach view before long press triggers
		parent.removeView(textView)

		shadowOf(Looper.getMainLooper()).idleFor(
			Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 100),
		)

		val up = MotionEvent.obtain(0, 600, MotionEvent.ACTION_UP, 10f, 10f, 0)
		parent.dispatchTouchEvent(up)
		shadowOf(Looper.getMainLooper()).idle()

		assertNull("Link should not be clicked after detachment", clickedUrl)
		assertFalse("Parent long click should not fire when view was detached", parentLongClicked)
	}
}
