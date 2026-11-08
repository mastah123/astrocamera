package com.astrocam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** A sensor "dark" picture: hot pixels and fixed noise, to be subtracted from lights. */
class DarkFrame(val width: Int, val height: Int, val pixels: IntArray)

/**
 * Dark frames are taken with the lens covered, at the same exposure and ISO as the real shots.
 * One averaged dark is kept per exposure and ISO.
 */
object DarkFrames {
    const val FRAMES = 16

    private fun file(context: Context, exposureNs: Long, iso: Int) =
        File(context.filesDir, "dark_${exposureNs / 100_000L}_$iso.png")

    fun exists(context: Context, exposureNs: Long, iso: Int): Boolean = file(context, exposureNs, iso).exists()

    fun load(context: Context, exposureNs: Long, iso: Int): DarkFrame? {
        val f = file(context, exposureNs, iso)
        if (!f.exists()) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = runCatching { BitmapFactory.decodeFile(f.path, options) }.getOrNull() ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return DarkFrame(w, h, px)
    }

    /** Averages the dark frames as they arrive. Feed it from one coroutine. */
    class Builder {
        private var width = 0
        private var height = 0
        private var sum: ShortArray? = null
        private var count = 0

        fun add(jpeg: ByteArray): Boolean {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }.getOrNull() ?: return false
            val w = bmp.width
            val h = bmp.height
            var s = sum
            if (s == null) {
                width = w
                height = h
                s = ShortArray(w * h * 3)
                sum = s
            } else if (w != width || h != height) {
                bmp.recycle()
                return false
            }
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            for (i in px.indices) {
                val p = px[i]
                val j = i * 3
                s[j] = (s[j] + ((p shr 16) and 0xFF)).toShort()
                s[j + 1] = (s[j + 1] + ((p shr 8) and 0xFF)).toShort()
                s[j + 2] = (s[j + 2] + (p and 0xFF)).toShort()
            }
            count++
            return true
        }

        val frames: Int get() = count

        /** Writes the averaged dark as a lossless picture. Returns false if nothing usable was captured. */
        fun save(context: Context, exposureNs: Long, iso: Int): Boolean {
            val s = sum
            if (s == null || count < 4) return false
            val out = IntArray(width * height)
            for (i in out.indices) {
                val j = i * 3
                val r = (s[j] + count / 2) / count
                val g = (s[j + 1] + count / 2) / count
                val b = (s[j + 2] + count / 2) / count
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(out, 0, width, 0, 0, width, height)
            val ok = runCatching {
                FileOutputStream(file(context, exposureNs, iso)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.getOrDefault(false)
            bmp.recycle()
            return ok
        }
    }
}
