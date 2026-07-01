/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.hush.music.db.entities.Song
import java.io.File
import java.io.FileWriter
import java.io.IOException

object PlaylistExporter {
    private const val EXPORT_SUBDIRECTORY = "HushExports"

    fun exportPlaylistAsCSV(
        context: Context,
        playlistName: String,
        songs: List<Song>,
    ): Result<File> =
        try {
            val csvContent =
                buildString {
                    append("Title,Artist,Album,YouTube Video ID\n")
                    songs.forEach { song ->
                        append("\"${song.song.title.replace("\"", "\"\"")}\"")
                        append(",")
                        append("\"${song.artists.joinToString("; ") { it.name.replace("\"", "\"\"") }}\"")
                        append(",")
                        append("\"${song.album?.title?.replace("\"", "\"\"") ?: ""}\"")
                        append(",")
                        append(song.song.id)
                        append("\n")
                    }
                }
            val file = createExportFile(context, "$playlistName.csv")
            FileWriter(file).use { it.write(csvContent) }
            Result.success(file)
        } catch (e: IOException) {
            Result.failure(e)
        }

    fun exportPlaylistAsM3U(
        context: Context,
        playlistName: String,
        songs: List<Song>,
    ): Result<File> =
        try {
            val m3uContent =
                buildString {
                    append("#EXTM3U\n")
                    songs.forEach { song ->
                        append("#EXTINF:${song.song.duration},")
                        append("${song.artists.joinToString(";") { it.name }} - ${song.song.title}")
                        append("\n")
                        append("https://youtube.com/watch?v=${song.song.id}\n")
                    }
                }
            val file = createExportFile(context, "$playlistName.m3u")
            FileWriter(file).use { it.write(m3uContent) }
            Result.success(file)
        } catch (e: IOException) {
            Result.failure(e)
        }

    private fun createExportFile(
        context: Context,
        filename: String,
    ): File {
        val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), EXPORT_SUBDIRECTORY)
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        val baseFilename = filename.substringBeforeLast('.')
        val extension = filename.substringAfterLast('.', "")
        var exportFile = File(exportDir, filename)
        var counter = 1

        while (exportFile.exists()) {
            val newFilename =
                if (extension.isNotEmpty()) {
                    "${baseFilename}_$counter.$extension"
                } else {
                    "${baseFilename}_$counter"
                }
            exportFile = File(exportDir, newFilename)
            counter++
        }

        exportFile.createNewFile()
        return exportFile
    }
}

fun getExportFileUri(
    context: Context,
    file: File,
): Uri =
    FileProvider.getUriForFile(
        context,
        "${context.packageName}.FileProvider",
        file,
    )

fun saveExportToPublicDocuments(
    context: Context,
    source: File,
    mimeType: String,
): Result<Uri> =
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath = Environment.DIRECTORY_DOCUMENTS + "/HushExports"

            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val destUri =
                resolver.insert(collection, values)
                    ?: return Result.failure(IOException("Failed to create destination in MediaStore"))

            resolver.openOutputStream(destUri)?.use { out ->
                source.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: return Result.failure(IOException("Failed to open output stream for MediaStore uri"))

            val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(destUri, complete, null, null)

            Result.success(destUri)
        } else {
            Result.success(getExportFileUri(context, source))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
