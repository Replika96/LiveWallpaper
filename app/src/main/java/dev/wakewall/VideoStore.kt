package dev.wakewall

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal data class VideoClip(val file: File, val title: String, val durationMs: Long)

internal object VideoStore {
    const val FILE = "file"
    const val ERROR_FILE = "error_file"
    private const val TITLE = "title"
    private const val DURATION = "duration"
    private val importLock = Mutex()

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)

    private fun directory(context: Context) = File(context.filesDir, "clips")

    fun current(context: Context): VideoClip? {
        val values = preferences(context).all
        val name = values[FILE] as? String ?: return null
        return VideoClip(
            File(directory(context), name),
            values[TITLE] as? String ?: "Видео.mp4",
            values[DURATION] as? Long ?: 0L,
        )
    }

    // A new file becomes visible to engines only after copying and validating it.
    // Keeping a private copy also works with cloud providers and removable storage.
    suspend fun import(context: Context, uri: Uri): VideoClip = withContext(Dispatchers.IO) {
        importLock.withLock {
            val directory = directory(context)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
            val previous = current(context)
            // Recover unfinished imports after process death.
            directory.listFiles()?.filter { it != previous?.file }?.forEach { it.delete() }
            val candidate = File.createTempFile("clip-", ".mp4", directory)
            var published = false
            try {
                val resolver = context.contentResolver
                val input = resolver.openInputStream(uri) ?: throw IOException()
                val jobContext = currentCoroutineContext()
                input.use { source ->
                    candidate.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            jobContext.ensureActive()
                            val count = source.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                val durationMs = inspectVideo(candidate)
                val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Видео.mp4"
                jobContext.ensureActive()
                val prefs = preferences(context)
                if (!prefs.edit()
                        .putString(FILE, candidate.name)
                        .putString(TITLE, title)
                        .putLong(DURATION, durationMs)
                        .remove(ERROR_FILE)
                        .commit()
                ) {
                    // SharedPreferences updates its in-memory map even if the disk write fails.
                    prefs.edit().putString(FILE, previous?.file?.name)
                        .putString(TITLE, previous?.title)
                        .putLong(DURATION, previous?.durationMs ?: 0).apply()
                    throw IOException()
                }
                published = true
                previous?.file?.delete()
                VideoClip(candidate, title, durationMs)
            } finally {
                if (!published) candidate.delete()
            }
        }
    }

    private fun inspectVideo(file: File): Long = MediaMetadataRetriever().use { metadata ->
        metadata.setDataSource(file.absolutePath)
        require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) == "video/mp4")
        require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
        val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        require(duration != null && duration > 0)
        // The container may contain a video track the device cannot actually decode.
        val frame = metadata.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64)
        requireNotNull(frame).recycle()
        duration
    }

    fun markPlaybackError(context: Context, file: File, failed: Boolean) {
        val prefs = preferences(context)
        val errorFile = if (failed) file.name else null
        if (prefs.getString(FILE, null) == file.name && prefs.getString(ERROR_FILE, null) != errorFile) {
            prefs.edit().putString(ERROR_FILE, errorFile).apply()
        }
    }
}
