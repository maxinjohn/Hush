package app.hush.music.audiosource

data class AudioCandidate(
    val providerId: String,
    val providerLabel: String,
    val trackId: String,
    val title: String,
    val artists: List<String>,
    val containerFormat: String? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrateKbps: Int? = null,
    val streamUrl: String? = null,
)
