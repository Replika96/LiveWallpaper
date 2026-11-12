package dev.wakewall

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy

/** One instance per Engine. All methods are called on the main looper. */
@OptIn(UnstableApi::class)
internal class WallpaperPlayback(private val context: Context, private var interactive: Boolean) {
    private var visible = false
    private var surface: Surface? = null
    private var file: File? = null
    private var player: ExoPlayer? = null
    private var resumeAtMs = 0L
    private var finished = false
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastBitmap: Bitmap? = null
    private var still: StillFrame? = null
    private var width = 0
    private var height = 0

    internal val showingStill get() = still != null
    internal val heldFrame get() = lastBitmap

    fun setInteractive(value: Boolean) {
        if (interactive == value) return
        interactive = value
        if (!value) resetRun()
        reconcile()
    }

    fun setVisible(value: Boolean) {
        visible = value
        reconcile()
    }

    fun setSurface(value: Surface?, width: Int = this.width, height: Int = this.height) {
        if (surface !== value) releasePlayer()
        if (surface !== value || width != this.width || height != this.height) clearStill()
        this.width = width
        this.height = height
        surface = value
        reconcile()
    }

    fun redrawSurface(output: Surface) {
        // A resize can invalidate the last buffer without replacing the Java Surface.
        if (finished && surface === output) {
            clearStill()
            releasePlayer()
        }
        setSurface(output)
    }

    fun setFile(value: File?) {
        if (file != value) {
            resetRun()
            lastBitmap = null
            file = value
        }
        reconcile()
    }

    private fun resetRun() {
        clearStill()
        releasePlayer()
        resumeAtMs = 0
        finished = false
        failed = false
    }

    private fun reconcile() {
        val output = surface
        val video = file
        if (!visible || !interactive || output == null || !output.isValid || video == null || failed) {
            clearStill()
            releasePlayer()
            return
        }
        val bitmap = lastBitmap
        if (finished && bitmap != null) {
            if (still == null) {
                releasePlayer()
                still = StillFrame().also { it.draw(output, bitmap, width, height) }
            }
            return
        }
        if (player != null) return

        val exo = ExoPlayer.Builder(context).build()
        player = exo
        val lastFrameUs = AtomicLong(0)
        exo.setVideoFrameMetadataListener { timeUs, _, _, _ -> lastFrameUs.set(timeUs) }
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (player === exo && tracks.groups.isNotEmpty() && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    playbackFailed(video)
                }
            }

            override fun onRenderedFirstFrame() {
                if (player !== exo) return
                VideoStore.markPlaybackError(context, video, false)
                if (finished && lastBitmap == null) captureFinalFrame(exo, output)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== exo || playbackState != Player.STATE_ENDED || finished) return
                // Keep the decoder attached. The compositor continues to show its last buffer.
                // Save the real video timestamp, including for files with a longer audio track.
                finished = true
                resumeAtMs = (lastFrameUs.get() / 1000).coerceAtLeast(0)
                exo.pause()
                if (lastBitmap == null) captureFinalFrame(exo, output) else reconcile()
                Log.d(TAG, "Ended, holding frame at ${resumeAtMs}ms")
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exo) return
                Log.w(TAG, "Wallpaper playback failed: ${error.errorCodeName}", error)
                playbackFailed(video)
            }
        })
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.volume = 0f
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        exo.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        exo.setSeekParameters(SeekParameters.EXACT)
        exo.setVideoSurface(output)
        val item = MediaItem.Builder().setUri(Uri.fromFile(video)).setMimeType(MimeTypes.VIDEO_MP4).build()
        exo.setMediaItem(item, resumeAtMs)
        // On a replacement surface the paused decoder renders the saved final frame once.
        exo.playWhenReady = !finished
        exo.prepare()
    }

    private fun captureFinalFrame(exo: ExoPlayer, output: Surface) {
        val size = exo.videoSize
        val w = (size.width * size.pixelWidthHeightRatio).toInt().takeIf { it > 0 } ?: width
        val h = size.height.takeIf { it > 0 } ?: height
        if (w <= 0 || h <= 0) return
        val bounds = context.resources.displayMetrics
        val scale = minOf(1f, maxOf(bounds.widthPixels, bounds.heightPixels).toFloat() / maxOf(w, h))
        val bitmap = Bitmap.createBitmap(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(output, bitmap, { result ->
                if (player === exo && finished && result == PixelCopy.SUCCESS) {
                    lastBitmap = bitmap
                    reconcile()
                } else {
                    bitmap.recycle()
                }
            }, handler)
        } catch (_: IllegalArgumentException) {
            // The host may already be tearing down this window. Keep its saved video position.
            bitmap.recycle()
        }
    }

    private fun playbackFailed(video: File) {
        failed = true
        VideoStore.markPlaybackError(context, video, true)
        releasePlayer()
    }

    private fun releasePlayer() {
        val old = player ?: return
        player = null
        if (!finished && !failed) resumeAtMs = old.currentPosition.coerceAtLeast(0)
        old.clearVideoSurface()
        old.release()
    }

    fun close() {
        clearStill()
        lastBitmap = null
        visible = false
        surface = null
        file = null
        releasePlayer()
    }

    private fun clearStill() {
        still?.close()
        still = null
    }

    companion object {
        private const val TAG = "WakeWall"
    }
}
