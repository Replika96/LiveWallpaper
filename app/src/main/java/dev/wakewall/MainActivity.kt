package dev.wakewall

import android.app.Application
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val model: VideoModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) model.select(uri)
            }
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFFA3DBBE), onPrimary = Color(0xFF12382C),
                    background = Color(0xFF111714), surface = Color(0xFF1B231F),
                    onSurfaceVariant = Color(0xFFC2CCC6), outlineVariant = Color(0xFF46534A))
            } else {
                lightColorScheme(primary = Color(0xFF215F4B), background = Color(0xFFF6F8F5),
                    surface = Color.White, onSurfaceVariant = Color(0xFF46534A),
                    outlineVariant = Color(0xFFCCD7CF))
            }
            MaterialTheme(colorScheme = colors) {
                WallpaperScreen(model, onChoose = {
                    model.notice = null
                    try {
                        picker.launch(arrayOf("video/mp4"))
                    } catch (_: ActivityNotFoundException) {
                        model.notice = R.string.no_picker
                    }
                }, onInstall = ::openInstaller)
            }
        }
    }

    private fun openInstaller() {
        val manager = WallpaperManager.getInstance(this)
        model.notice = null
        try {
            if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) {
                model.notice = R.string.install_blocked
                return
            }
            val preview = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, VideoWallpaperService::class.java),
            )
            try {
                startActivity(preview)
            } catch (_: ActivityNotFoundException) {
                model.notice = R.string.choose_service
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            }
        } catch (_: ActivityNotFoundException) {
            model.notice = R.string.no_installer
        } catch (_: SecurityException) {
            model.notice = R.string.install_blocked
        }
    }
}

internal class VideoModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = VideoStore.preferences(app)
    var clip by mutableStateOf(VideoStore.current(app))
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
    var playbackFailed by mutableStateOf(hasPlaybackError())
        private set

    private fun hasPlaybackError() = clip != null && prefs.getString(VideoStore.ERROR_FILE, null) == clip?.file?.name

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == VideoStore.FILE) clip = VideoStore.current(app)
        if (key == VideoStore.FILE || key == VideoStore.ERROR_FILE) playbackFailed = hasPlaybackError()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun select(uri: Uri) {
        if (busy) return
        busy = true
        notice = null
        viewModelScope.launch {
            try {
                clip = VideoStore.import(app, uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                notice = R.string.import_failed
            } catch (_: SecurityException) {
                notice = R.string.import_failed
            } catch (_: RuntimeException) {
                notice = R.string.invalid_video
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}

@Composable
private fun WallpaperScreen(model: VideoModel, onChoose: () -> Unit, onInstall: () -> Unit) {
    val clip = model.clip
    val message = model.notice ?: if (model.playbackFailed) R.string.playback_failed else null
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())
                .widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.intro), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            OutlinedCard(shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ClipThumbnail(clip)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(clip?.title ?: stringResource(R.string.no_video), style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val seconds = (clip?.durationMs ?: 0) / 1000
                            Text(if (clip == null) stringResource(R.string.file_hint)
                                else stringResource(R.string.video_details, seconds / 60, seconds % 60),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(onClick = onChoose, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                        if (model.busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(when {
                            model.busy -> R.string.importing
                            clip != null -> R.string.replace
                            else -> R.string.choose
                        }))
                    }
                }
            }
            if (message != null) {
                Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onInstall, enabled = clip != null && !model.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.install))
            }
            Text(stringResource(R.string.install_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (clip != null) Text(stringResource(R.string.selection_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClipThumbnail(clip: VideoClip?) {
    val image by produceState<ImageBitmap?>(null, clip) {
        value = null
        if (clip != null) value = withContext(Dispatchers.IO) {
            try {
                ThumbnailUtils.createVideoThumbnail(clip.file, Size(180, 240), null).asImageBitmap()
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }
    Box(Modifier.size(width = 60.dp, height = 80.dp).clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF153830)), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap == null) {
            Image(painterResource(R.drawable.ic_wallpaper), contentDescription = null, modifier = Modifier.size(44.dp))
        } else {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
