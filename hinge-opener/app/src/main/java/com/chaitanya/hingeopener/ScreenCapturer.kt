package com.chaitanya.hingeopener

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * Keeps one VirtualDisplay mirroring the screen into an ImageReader for the whole session
 * (one consent, many captures). Always holds the most recent frame; capture() encodes it.
 */
class ScreenCapturer(
    context: Context,
    private val projection: MediaProjection,
    onStopped: () -> Unit,
) {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private val reader: ImageReader
    private val display: VirtualDisplay
    private val width: Int
    private val height: Int

    /** Only touched on [handler]'s thread. */
    private var latest: Image? = null

    init {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            width = dm.widthPixels
            height = dm.heightPixels
        }
        val dpi = context.resources.displayMetrics.densityDpi

        // Android 14+ requires the callback before createVirtualDisplay.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post(onStopped)
            }
        }, main)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            latest?.close()
            latest = img
        }, handler)

        display = projection.createVirtualDisplay(
            "hinge-opener-capture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
    }

    /** Encodes the latest frame as a JPEG ≤ [maxWidth] px wide into [out]. Callback runs on main. */
    fun capture(out: File, maxWidth: Int, callback: (Result<File>) -> Unit) {
        handler.post {
            val result = runCatching {
                val img = latest ?: throw IllegalStateException("No screen frame yet. Try again.")
                val bitmap = toBitmap(img)
                if (looksBlack(bitmap)) {
                    bitmap.recycle()
                    throw IllegalStateException("Capture came out black. Hinge may be blocking screenshots.")
                }
                val scaled = if (bitmap.width > maxWidth) {
                    val h = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt()
                    Bitmap.createScaledBitmap(bitmap, maxWidth, h, true).also { bitmap.recycle() }
                } else bitmap
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                scaled.recycle()
                out
            }
            main.post { callback(result) }
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val buffer = plane.buffer.apply { rewind() }
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(buffer)
        if (padded.width == img.width) return padded
        return Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
    }

    private fun looksBlack(b: Bitmap): Boolean {
        val steps = 12
        for (yi in 1 until steps) for (xi in 1 until steps) {
            val p = b.getPixel(b.width * xi / steps, b.height * yi / steps)
            val sum = (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)
            if (sum > 30) return false
        }
        return true
    }

    fun release() {
        handler.post {
            latest?.close()
            latest = null
            display.release()
            reader.close()
            thread.quitSafely()
        }
        runCatching { projection.stop() }
    }
}
