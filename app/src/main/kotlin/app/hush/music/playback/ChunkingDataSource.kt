package app.hush.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Wraps a data source to transparently split reads into fixed-size HTTP range
 * requests. This bypasses YouTube's per-request download throttling.
 * Adapted from Echo Music (EchoMusicApp/Echo-Music).
 */
class ChunkingDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long,
) : DataSource {

    private var dataSpec: DataSpec? = null
    private var bytesToRead: Long = C.LENGTH_UNSET.toLong()
    private var bytesReadTotal: Long = 0
    private var isOpened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.bytesReadTotal = 0
        this.bytesToRead = dataSpec.length
        this.isOpened = true
        openNextChunk()
        return bytesToRead
    }

    private fun openNextChunk() {
        val spec = this.dataSpec ?: throw IOException("DataSpec is null")
        val position = spec.position + bytesReadTotal

        val length = if (bytesToRead == C.LENGTH_UNSET.toLong()) {
            chunkSize
        } else {
            val remaining = bytesToRead - bytesReadTotal
            if (remaining == 0L) return
            minOf(chunkSize, remaining)
        }

        upstream.open(
            spec.buildUpon()
                .setPosition(position)
                .setLength(length)
                .build(),
        )
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!isOpened) return C.RESULT_END_OF_INPUT
        if (bytesToRead != C.LENGTH_UNSET.toLong() && bytesReadTotal >= bytesToRead) {
            return C.RESULT_END_OF_INPUT
        }

        val bytes = try {
            upstream.read(buffer, offset, readLength)
        } catch (_: Exception) {
            -1
        }

        if (bytes == C.RESULT_END_OF_INPUT || bytes == -1) {
            upstream.close()
            try {
                openNextChunk()
            } catch (e: InvalidResponseCodeException) {
                if (e.responseCode == 416) return C.RESULT_END_OF_INPUT
                throw e
            } catch (e: DataSourceException) {
                if (e.reason == DataSourceException.POSITION_OUT_OF_RANGE) return C.RESULT_END_OF_INPUT
                throw e
            }
            return try {
                val newBytes = upstream.read(buffer, offset, readLength)
                if (newBytes == C.RESULT_END_OF_INPUT || newBytes == -1) {
                    C.RESULT_END_OF_INPUT
                } else {
                    bytesReadTotal += newBytes
                    newBytes
                }
            } catch (_: Exception) {
                C.RESULT_END_OF_INPUT
            }
        }
        bytesReadTotal += bytes
        return bytes
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        isOpened = false
        upstream.close()
    }
}

class ChunkingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val chunkSize: Long = 5L * 1024 * 1024,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        ChunkingDataSource(upstreamFactory.createDataSource(), chunkSize)
}
