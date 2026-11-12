package dev.wakewall

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.Surface

/** Draw once and keep the buffer attached until the wallpaper is hidden or replaced. */
internal class StillFrame {
    private val renderer = HardwareRenderer()
    private val node = RenderNode("Wallpaper final frame")

    fun draw(surface: Surface, bitmap: Bitmap, width: Int, height: Int) {
        val w = width.takeIf { it > 0 } ?: bitmap.width
        val h = height.takeIf { it > 0 } ?: bitmap.height
        val scale = maxOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val left = (w - bitmap.width * scale) / 2
        val top = (h - bitmap.height * scale) / 2
        node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording()
        canvas.drawBitmap(bitmap, null, RectF(left, top, w - left, h - top), Paint(Paint.FILTER_BITMAP_FLAG))
        node.endRecording()
        renderer.setContentRoot(node)
        renderer.setSurface(surface)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
    }

    fun close() {
        renderer.setSurface(null)
        renderer.destroy()
        node.discardDisplayList()
    }
}
