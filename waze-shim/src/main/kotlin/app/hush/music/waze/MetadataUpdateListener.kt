package app.hush.music.waze

interface MetadataUpdateListener {
    fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        duration: Long,
        position: Long,
        state: Int,
        artworkUrl: String?,
    )
}
