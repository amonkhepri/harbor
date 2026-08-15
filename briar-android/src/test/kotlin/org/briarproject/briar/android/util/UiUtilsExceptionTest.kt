package org.briarproject.briar.android.util

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UiUtilsExceptionTest {

	@Test
	fun `user message omits exception detail`() {
		val path = "/data/user/0/org.briarproject.briar/app_db/account"
		val exception = Exception(path, IOException(path))

		val message = UiUtils.formatExceptionForUser(exception)

		assertEquals("Error: Exception caused by IOException", message)
		assertFalse(message.contains(path))
	}
}
