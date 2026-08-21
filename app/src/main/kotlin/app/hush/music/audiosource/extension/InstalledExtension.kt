package app.hush.music.audiosource.extension

data class InstalledExtension(
    val extensionId: String,
    val repositoryId: String,
    val displayName: String,
    val description: String,
    val version: String,
    val category: String,
    val tags: List<String>,
    val downloadUrl: String,
    val sha256: String,
    val manifest: ExtensionManifest = ExtensionManifest(),
) {
    val hasSignedSession: Boolean
        get() = manifest.signedSession != null

    val requiresSession: Boolean
        get() = hasSignedSession
}
