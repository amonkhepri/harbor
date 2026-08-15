package org.briarproject.briar.telegram

import java.io.File

@Throws(ReflectiveOperationException::class)
internal fun createTelegramTdlibParametersRequest(
	tdlibKeyProvider: TelegramTdlibDatabaseKeyProvider,
	tdlibDirectory: File,
	apiId: Int,
	apiHash: String,
): Any? {
	val databaseEncryptionKey =
		tdlibKeyProvider.getDatabaseEncryptionKey(tdlibDirectory) ?: return null
	val databaseDirectory = File(tdlibDirectory, "database")
	val filesDirectory = File(tdlibDirectory, "files")
	databaseDirectory.mkdirs()
	filesDirectory.mkdirs()
	val request = createTdApiObject("SetTdlibParameters")
	setFieldIfPresent(request, "useTestDc", false)
	setFieldIfPresent(request, "databaseDirectory", databaseDirectory.path)
	setFieldIfPresent(request, "filesDirectory", filesDirectory.path)
	setFieldIfPresent(request, "databaseEncryptionKey", databaseEncryptionKey)
	setFieldIfPresent(request, "useFileDatabase", true)
	setFieldIfPresent(request, "useChatInfoDatabase", true)
	setFieldIfPresent(request, "useMessageDatabase", true)
	setFieldIfPresent(request, "useSecretChats", true)
	setFieldIfPresent(request, "apiId", apiId)
	setFieldIfPresent(request, "apiHash", apiHash)
	setFieldIfPresent(request, "systemLanguageCode", "en")
	setFieldIfPresent(request, "deviceModel", "Harbor Android")
	setFieldIfPresent(request, "systemVersion", "Android")
	setFieldIfPresent(request, "applicationVersion", "Harbor")
	return request
}
