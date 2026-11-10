package dev.wakewall

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class WallpaperPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private lateinit var work: File
    private lateinit var fixture: File
    private lateinit var playback: WallpaperPlayback
    private val outputs = mutableListOf<FrameCapture>()

    @Before fun setup() {
        work = Files.createTempDirectory(app.cacheDir.toPath(), "wake-test").toFile()
        fixture = File(work, "sample.mp4")
        instrumentation.context.assets.open("wake-cycle.mp4").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        onMain { playback = WallpaperPlayback(app, interactive = false) }
    }

    @After fun teardown() {
        onMain { playback.close() }
        outputs.forEach { it.close() }
        work.deleteRecursively()
    }

    // The H.264 fixture has five dark frames, twelve grey frames and just one white frame.
    @Test fun holdsFinalFrameAcrossSurfaceLossAndReplaysOnlyOnWake() {
        val first = newOutput()
        onMain {
            playback.setFile(fixture)
            playback.setSurface(first.surface)
            playback.setVisible(true)
        }
        SystemClock.sleep(150)
        assertTrue(first.levels.isEmpty())
        onMain { playback.setInteractive(true) }
        first.awaitWhite()
        assertTrue(first.levels.first() < 60)
        SystemClock.sleep(250)
        val countAtEnd = first.levels.size
        onMain {
            playback.setVisible(true)
            playback.setInteractive(true)
        }
        SystemClock.sleep(2000)
        assertEquals("Captured levels: ${first.levels}", countAtEnd, first.levels.size)
        assertTrue("Final texture after two seconds", first.displayedLevel() > 185)

        val replacement = newOutput()
        onMain {
            playback.setVisible(false)
            playback.setSurface(null)
            playback.setSurface(replacement.surface)
            playback.setVisible(true)
        }
        replacement.awaitWhite()
        assertTrue("Restored frames: ${replacement.levels}", replacement.levels.all { it > 185 })
        val previousCount = replacement.levels.size
        onMain { playback.redrawSurface(replacement.surface) }
        await("redrawn final frame") { replacement.levels.size > previousCount }
        assertTrue(replacement.levels.all { it > 185 })
        assertTrue("Final texture after redraw", replacement.displayedLevel() > 185)

        onMain {
            playback.setInteractive(false)
            playback.setSurface(null)
            playback.setInteractive(true)
        }
        val nextWake = newOutput()
        onMain { playback.setSurface(nextWake.surface) }
        nextWake.awaitWhite()
        assertTrue("Next wake: ${nextWake.levels}", nextWake.levels.first() < 60)
    }

    @Test fun temporaryInvisibilityResumesAndSleepHalfwayRearms() {
        val original = newOutput()
        onMain {
            playback.setInteractive(true)
            playback.setVisible(true)
            playback.setFile(fixture)
            playback.setSurface(original.surface)
        }
        await("grey frame") { original.levels.any { it in 75..145 } }
        onMain { playback.setVisible(false) }
        val resumed = newOutput()
        onMain {
            playback.setSurface(resumed.surface)
            playback.setVisible(true)
        }
        await("resumed frame") { resumed.levels.isNotEmpty() }
        assertTrue("Resumed frames: ${resumed.levels}", resumed.levels.first() in 75..145)
        onMain {
            playback.setInteractive(false)
            playback.setVisible(false)
            playback.setInteractive(true)
        }
        val nextWake = newOutput()
        onMain {
            playback.setSurface(nextWake.surface)
            playback.setVisible(true)
        }
        nextWake.awaitWhite()
        assertTrue(nextWake.levels.first() < 60)
    }

    @Test fun invalidImportPreservesPreviousPrivateCopy() = runBlocking {
        // Use separate storage even if the user has already configured the real wallpaper.
        val isolated = object : ContextWrapper(app) {
            override fun getFilesDir() = work
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("test_${work.name}_$name", Context.MODE_PRIVATE)
        }
        try {
            val imported = VideoStore.import(isolated, Uri.fromFile(fixture))
            assertEquals(1800L, imported.durationMs)
            assertTrue(imported.file.readBytes().contentEquals(fixture.readBytes()))
            fixture.writeText("invalid MP4")
            assertTrue(runCatching { VideoStore.import(isolated, Uri.fromFile(fixture)) }.isFailure)
            assertEquals(imported, VideoStore.current(isolated))
            assertEquals(listOf(imported.file), imported.file.parentFile!!.listFiles()!!.toList())
        } finally {
            app.deleteSharedPreferences("test_${work.name}_wallpaper")
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun newOutput() = FrameCapture().also(outputs::add)

    private fun FrameCapture.awaitWhite() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while ((levels.lastOrNull() ?: 0) <= 185 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(15)
        assertTrue("Expected white, recorded $levels", (levels.lastOrNull() ?: 0) > 185)
    }

    companion object {
        private fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(15)
            assertTrue(description, condition())
        }
    }
}
