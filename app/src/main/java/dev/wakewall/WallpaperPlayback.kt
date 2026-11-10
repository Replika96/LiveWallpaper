package dev.wakewall

import android.content.Context
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

    fun setSurface(value: Surface?) {
        if (surface !== value) releasePlayer()
        surface = value
        reconcile()
    }

    fun redrawSurface(output: Surface) {
        // A resize can invalidate the last buffer without replacing the Java Surface.
        if (finished && surface === output) releasePlayer()
        setSurface(output)
    }

    fun setFile(value: File?) {
        if (file != value) {
            resetRun()
            file = value
        }
        reconcile()
    }

    private fun resetRun() {
        releasePlayer()
        resumeAtMs = 0
        finished = false
        failed = false
    }

    private fun reconcile() {
        val output = surface
        val video = file
        if (!visible || !interactive || output == null || !output.isValid || video == null || failed) {
            releasePlayer()
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
                if (player === exo) VideoStore.markPlaybackError(context, video, false)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== exo || playbackState != Player.STATE_ENDED || finished) return
                // Keep the decoder attached. The compositor continues to show its last buffer.
                // Save the real video timestamp, including for files with a longer audio track.
                finished = true
                resumeAtMs = (lastFrameUs.get() / 1000).coerceAtLeast(0)
                exo.pause()
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
        visible = false
        surface = null
        file = null
        releasePlayer()
    }

    companion object {
        private const val TAG = "WakeWall"
    }
}
