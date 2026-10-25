package org.briarproject.briar.android.connector

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/** Advertise edits only inside input dispatch, never setText or saved-state restoration. */
class ConnectorComposeText(context: Context, attrs: AttributeSet?) :
	AppCompatEditText(context, attrs) {
	var onUserEdit: ((Boolean) -> Unit)? = null
	private var inputDepth = 0

	override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
		super.onTextChanged(text, start, before, count)
		if (text.isNullOrBlank()) onUserEdit?.invoke(false)
	}

	private fun input(action: () -> Boolean): Boolean {
		if (onUserEdit == null) return action()
		val before = text?.toString()
		inputDepth++
		try {
			return action()
		} finally {
			inputDepth--
			if (inputDepth == 0 && before != text?.toString()) {
				onUserEdit?.invoke(!text.isNullOrBlank())
			}
		}
	}

	override fun dispatchKeyEvent(event: KeyEvent) = input { super.dispatchKeyEvent(event) }
	override fun onTextContextMenuItem(id: Int) = input { super.onTextContextMenuItem(id) }
	override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
		val target = super.onCreateInputConnection(outAttrs) ?: return null
		return object : InputConnectionWrapper(target, false) {
			override fun commitText(text: CharSequence?, newCursorPosition: Int) =
				input { super.commitText(text, newCursorPosition) }
			override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
				input { super.setComposingText(text, newCursorPosition) }
			override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
				input { super.deleteSurroundingText(beforeLength, afterLength) }
			override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
				input { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
		}
	}
}
