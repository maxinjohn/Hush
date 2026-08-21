package app.hush.music.audiosource.extension

data class ExtensionRuntime(
    val relayBaseUrl: String,
    val downloadPath: String,
    val providerKey: String,
) {
    val isV1Anonymous: Boolean
        get() = !relayBaseUrl.contains("/v2")
}
