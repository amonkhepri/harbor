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
	val request = Class.forName("org.drinkless.tdlib.TdApi\$SetTdlibParameters")
		.getConstructor()
		.newInstance()
	setTdlibParameterField(request, "useTestDc", false)
	setTdlibParameterField(request, "databaseDirectory", databaseDirectory.path)
	setTdlibParameterField(request, "filesDirectory", filesDirectory.path)
	setTdlibParameterField(request, "databaseEncryptionKey", databaseEncryptionKey)
	setTdlibParameterField(request, "useFileDatabase", true)
	setTdlibParameterField(request, "useChatInfoDatabase", true)
	setTdlibParameterField(request, "useMessageDatabase", true)
	setTdlibParameterField(request, "useSecretChats", true)
	setTdlibParameterField(request, "apiId", apiId)
	setTdlibParameterField(request, "apiHash", apiHash)
	setTdlibParameterField(request, "systemLanguageCode", "en")
	setTdlibParameterField(request, "deviceModel", "Harbor Android")
	setTdlibParameterField(request, "systemVersion", "Android")
	setTdlibParameterField(request, "applicationVersion", "Harbor")
	return request
}

@Throws(ReflectiveOperationException::class)
private fun setTdlibParameterField(target: Any, name: String, value: Any) {
	try {
		target.javaClass.getField(name).set(target, value)
	} catch (_: NoSuchFieldException) {
	}
}
