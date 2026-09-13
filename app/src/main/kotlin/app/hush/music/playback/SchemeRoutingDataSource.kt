/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Sends each open either straight at the file or through the cache chain, based on the
 * scheme of the URI it was finally asked to open.
 *
 * This is the second half of [PlaybackDataSourceRouting], and it has to sit *below* a
 * `ResolvingDataSource` - the whole point is that [open] sees the scheme the resolver
 * produced. Sitting above the resolver (which is what both the playback and download
 * chains used to do) means it sees a bare song id with no scheme, always answers
 * [PlaybackByteSource.CACHED], and quietly feeds a file that is already on disk through
 * `CacheDataSource` to be copied into a cache under the song's key.
 *
 * Shared by playback and downloads so the two paths cannot disagree about which bytes
 * deserve a cache entry.
 */
class SchemeRoutingDataSource(
    private val cachedFactory: DataSource.Factory,
    private val directFactory: DataSource.Factory,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // [dataSpec] is already resolved by the time it reaches this router, so the
        // scheme here is the one that decides whether any cache is involved at all.
        val selectedFactory =
            when (PlaybackDataSourceRouting.routeResolvedUri(dataSpec.uri.scheme)) {
                PlaybackByteSource.LOCAL_FILE -> directFactory
                PlaybackByteSource.CACHED -> cachedFactory
            }
        val selectedDataSource = selectedFactory.createDataSource()
        transferListeners.forEach(selectedDataSource::addTransferListener)
        delegate = selectedDataSource
        return selectedDataSource.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(delegate).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
