package dev.wakewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine() {
        private val power = getSystemService(PowerManager::class.java)
        private val prefs = VideoStore.preferences(this@VideoWallpaperService)
        private lateinit var playback: WallpaperPlayback
        private var destroyed = false

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (destroyed) return
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> playback.setInteractive(false)
                    Intent.ACTION_SCREEN_ON -> playback.setInteractive(true)
                }
            }
        }
        private val selectionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (!destroyed && key == VideoStore.FILE) {
                playback.setFile(VideoStore.current(this@VideoWallpaperService)?.file)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            playback = WallpaperPlayback(applicationContext, power.isInteractive)
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            prefs.registerOnSharedPreferenceChangeListener(selectionListener)
            playback.setFile(VideoStore.current(this@VideoWallpaperService)?.file)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            if (!destroyed) playback.setSurface(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (!destroyed) playback.setSurface(holder.surface)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (!destroyed) playback.redrawSurface(holder.surface)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (destroyed) return
            // Visibility can precede the screen broadcast. Becoming visible by itself is not a wake.
            if (!visible) playback.setVisible(false)
            playback.setInteractive(power.isInteractive)
            if (visible) playback.setVisible(true)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            if (!destroyed) playback.setSurface(null)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true
            prefs.unregisterOnSharedPreferenceChangeListener(selectionListener)
            unregisterReceiver(screenReceiver)
            playback.close()
            super.onDestroy()
        }
    }
}
