package org.briarproject.briar.android.connector

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewParent
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.briarproject.briar.android.widget.LinkDialogFragment
import java.util.regex.Pattern
import kotlin.math.abs

internal object ConnectorMessageLinks {

	private val SCHEME_NORMALIZER = Linkify.TransformFilter { _, url ->
		if (url.startsWith("http://", ignoreCase = true)) {
			"http://" + url.substring(7)
		} else if (url.startsWith("https://", ignoreCase = true)) {
			"https://" + url.substring(8)
		} else {
			url
		}
	}

	private val WEB_URL_PATTERN: Pattern = Pattern.compile(
		"https?://[a-zA-Z0-9+&@#/%?=~_|!:,.;()\\-]*[a-zA-Z0-9+&@#/%=~_|]",
		Pattern.CASE_INSENSITIVE,
	)

	fun linkify(textView: TextView, onLinkClicked: ((String) -> Unit)? = null) {
		val text = textView.text ?: return
		val spannable = if (text is Spannable) text else SpannableString(text)
		val hasLinks = Linkify.addLinks(
			spannable,
			WEB_URL_PATTERN,
			null,
			null,
			SCHEME_NORMALIZER,
		)
		if (hasLinks) {
			val existingUrlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
			for (span in existingUrlSpans) {
				val start = spannable.getSpanStart(span)
				val end = spannable.getSpanEnd(span)
				val flags = spannable.getSpanFlags(span)
				val url = span.url
				spannable.removeSpan(span)
				spannable.setSpan(WebUrlSpan(url, onLinkClicked), start, end, flags)
			}
			if (text !is Spannable) {
				textView.text = spannable
			}
			textView.movementMethod = LinkTouchMovementMethod.getInstance()
		} else {
			textView.movementMethod = null
		}
		textView.isFocusable = false
		textView.isClickable = false
		textView.isLongClickable = false
	}

	internal open class WebUrlSpan(
		url: String,
		private val onLinkClicked: ((String) -> Unit)? = null,
	) : URLSpan(url) {
		override fun onClick(widget: View) {
			if (onLinkClicked != null) {
				onLinkClicked.invoke(url)
			} else {
				showLinkDialog(widget, url)
			}
		}
	}

	internal fun showLinkDialog(view: View, url: String) {
		val activity = findFragmentActivity(view.context) ?: return
		val fm = activity.supportFragmentManager
		if (!fm.isStateSaved && !fm.isDestroyed) {
			val dialog = LinkDialogFragment.newInstance(url)
			dialog.show(fm, dialog.uniqueTag)
		}
	}

	private fun findFragmentActivity(context: Context): FragmentActivity? {
		var ctx: Context? = context
		while (ctx is ContextWrapper) {
			if (ctx is FragmentActivity) return ctx
			ctx = ctx.baseContext
		}
		return null
	}

	internal class LinkTouchMovementMethod private constructor() : LinkMovementMethod() {

		private val handler = Handler(Looper.getMainLooper())
		private var pendingLongClick: Runnable? = null
		private var longClickPerformed = false
		private var downX = 0f
		private var downY = 0f
		private var isDragging = false

		override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
			val action = event.actionMasked
			if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
				var x = event.x.toInt()
				var y = event.y.toInt()

				x -= widget.totalPaddingLeft
				y -= widget.totalPaddingTop

				x += widget.scrollX
				y += widget.scrollY

				val layout = widget.layout ?: return false
				val line = layout.getLineForVertical(y)
				if (y < layout.getLineTop(line) || y > layout.getLineBottom(line)) {
					cancelPendingLongClick()
					Selection.removeSelection(buffer)
					return false
				}
				val off = layout.getOffsetForHorizontal(line, x.toFloat())

				val links = buffer.getSpans(off, off, ClickableSpan::class.java)

				if (links.isNotEmpty()) {
					val lineLeft = layout.getLineLeft(line)
					val lineRight = layout.getLineRight(line)
					if (x < lineLeft || x > lineRight) {
						cancelPendingLongClick()
						Selection.removeSelection(buffer)
						return false
					}

					val link = links[0]
					val spanStart = buffer.getSpanStart(link)
					val spanEnd = buffer.getSpanEnd(link)
					if (off < spanStart || off > spanEnd) {
						cancelPendingLongClick()
						Selection.removeSelection(buffer)
						return false
					}

					if (action == MotionEvent.ACTION_DOWN) {
						downX = event.rawX
						downY = event.rawY
						isDragging = false
						longClickPerformed = false
						Selection.setSelection(buffer, spanStart, spanEnd)
						val runnable = Runnable {
							if (!widget.isAttachedToWindow) {
								cancelPendingLongClick()
								return@Runnable
							}
							longClickPerformed = true
							Selection.removeSelection(buffer)
							var current: ViewParent? = widget.parent
							var handled = false
							while (current != null) {
								val view = current as? View
								if (view != null && view.performLongClick()) {
									handled = true
									break
								}
								current = current.parent
							}
							if (!handled) {
								widget.performLongClick()
							}
						}
						pendingLongClick = runnable
						handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
						return true
					} else if (action == MotionEvent.ACTION_UP) {
						cancelPendingLongClick()
						Selection.removeSelection(buffer)
						if (!longClickPerformed && !isDragging) {
							link.onClick(widget)
						}
						return true
					}
				} else {
					cancelPendingLongClick()
					Selection.removeSelection(buffer)
					return false
				}
			} else if (action == MotionEvent.ACTION_MOVE) {
				val slop = ViewConfiguration.get(widget.context).scaledTouchSlop
				val dx = abs(event.rawX - downX)
				val dy = abs(event.rawY - downY)
				if (dx > slop || dy > slop) {
					isDragging = true
					cancelPendingLongClick()
					Selection.removeSelection(buffer)
				}
			} else if (action == MotionEvent.ACTION_CANCEL) {
				cancelPendingLongClick()
				Selection.removeSelection(buffer)
				return false
			}

			return false
		}

		private fun cancelPendingLongClick() {
			pendingLongClick?.let {
				handler.removeCallbacks(it)
				pendingLongClick = null
			}
		}

		companion object {
			private val instance = LinkTouchMovementMethod()

			fun getInstance(): LinkTouchMovementMethod = instance
		}
	}
}
