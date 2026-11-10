package dev.wakewall

import android.graphics.SurfaceTexture
import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Samples the displayed RGB texture. Codec-specific YUV buffer layouts differ across devices. */
internal class FrameCapture : Closeable {
    val levels = CopyOnWriteArrayList<Int>()
    private val thread = HandlerThread("capture-test-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
    private val pixel = ByteBuffer.allocateDirect(4)
    private lateinit var texture: SurfaceTexture
    val surface: Surface = onRenderThread {
        check(eglInitialize(display, IntArray(1), 0, IntArray(1), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val configAttrs = intArrayOf(EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE)
        check(eglChooseConfig(display, configAttrs, 0, configs, 0, 1, IntArray(1), 0))
        val ctx = eglCreateContext(display, configs[0], EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE), 0)
        val pbuffer = eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE), 0)
        check(eglMakeCurrent(display, pbuffer, pbuffer, ctx))

        val program = glCreateProgram()
        attach(program, GL_VERTEX_SHADER, "void main(){ gl_Position=vec4(0.,0.,0.,1.); gl_PointSize=1.; }")
        attach(program, GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES video;
            void main(){ gl_FragColor=texture2D(video, vec2(0.5)); }
        """.trimIndent())
        glLinkProgram(program)
        val linked = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        check(linked[0] == GL_TRUE) { glGetProgramInfoLog(program) }
        glUseProgram(program)
        glViewport(0, 0, 1, 1)
        val names = IntArray(1)
        glGenTextures(1, names, 0)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, names[0])
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        texture = SurfaceTexture(names[0]).apply { setDefaultBufferSize(96, 160) }
        texture.setOnFrameAvailableListener({
            texture.updateTexImage()
            levels.add(readPixel())
        }, handler)
        Surface(texture)
    }

    // Read the displayed texture again even when the decoder has produced no new buffer.
    fun displayedLevel() = onRenderThread { readPixel() }

    private fun readPixel(): Int {
        glDrawArrays(GL_POINTS, 0, 1)
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel)
        return pixel.get(0).toInt() and 0xff
    }

    private fun attach(program: Int, type: Int, source: String) {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        glAttachShader(program, shader)
        glDeleteShader(shader)
    }

    private fun <T> onRenderThread(block: () -> T): T {
        val task = FutureTask(block)
        handler.post(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        onRenderThread {
            texture.setOnFrameAvailableListener(null)
            surface.release()
            texture.release()
            val buffer = eglGetCurrentSurface(EGL_DRAW)
            val ctx = eglGetCurrentContext()
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
            eglDestroySurface(display, buffer)
            eglDestroyContext(display, ctx)
            eglTerminate(display)
            eglReleaseThread()
        }
        thread.quitSafely()
        thread.join()
    }
}
