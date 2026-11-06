package com.astrocam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class StackMode(val label: String, val hint: String) {
    Average("Align and average", "Lines up the stars and averages the frames to cut noise"),
    Trails("Star trails", "Keeps the brightest pixels of every frame, with no alignment"),
}

enum class Stretch(val label: String, val gamma: Float?) {
    Off("Off", null),
    Medium("Medium", 0.7f),
    Strong("Strong", 0.5f),
}

class StackResult(
    val original: Bitmap?,
    val stacked: Bitmap?,
    val used: Int,
    val rejected: Int,
    val savedName: String?,
    val message: String,
)

private class Star(val x: Float, val y: Float)
private class Candidate(val x: Int, val y: Int, val v: Float)
private class Placed(val ref: FrameRef, val sx: Int, val sy: Int)

/**
 * Aligns frames by matching stars (translation only), then averages them or keeps the
 * brightest pixels. Works in two passes so only the needed frames are decoded at full size.
 */
object Stacker {
    private const val MAX_FRAMES = 250        // 16-bit sums hold 257 frames of 8-bit values
    private const val MAX_SHIFT_HALF = 100    // search range in half-resolution pixels
    private const val STAR_COUNT = 60
    private const val PREVIEW_MAX = 1280

    suspend fun stack(
        context: Context,
        session: SessionInfo,
        mode: StackMode,
        stretch: Stretch,
        onProgress: (String, Float) -> Unit,
    ): StackResult = withContext(Dispatchers.Default) { run(context, session, mode, stretch, onProgress) }

    private fun failure(message: String) = StackResult(null, null, 0, 0, null, message)

    private suspend fun run(
        context: Context,
        session: SessionInfo,
        mode: StackMode,
        stretch: Stretch,
        onProgress: (String, Float) -> Unit,
    ): StackResult {
        val refs = session.frames.take(MAX_FRAMES)
        val total = refs.size
        val (width, height) = refs.firstNotNullOfOrNull { bounds(context, it.location) }
            ?: return failure("Could not read any frame. Were the photos deleted?")
        val average = mode == StackMode.Average

        // Pass 1: find each frame's shift against the first frame.
        val placed = mutableListOf<Placed>()
        var rejected = 0
        var note = ""
        if (average) {
            var refStars: List<Star>? = null
            var refHalf: Pair<Int, Int>? = null
            var alignable = true
            for ((index, ref) in refs.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress("Finding stars ${index + 1} of $total", 0.3f * index / total)
                val half = decode(context, ref.location, 2)
                if (half == null) { rejected++; continue }
                val size = half.width to half.height
                val stars = detectStars(half)
                if (refStars == null) {
                    refStars = stars
                    refHalf = size
                    alignable = stars.size >= 5
                    if (!alignable) {
                        note = "Not enough stars to align, so frames were stacked without alignment. "
                    }
                    placed += Placed(ref, 0, 0)
                } else if (!alignable) {
                    placed += Placed(ref, 0, 0)
                } else if (size != refHalf) {
                    rejected++
                } else {
                    val shift = estimateShift(refStars, stars)
                    if (shift == null) rejected++
                    else placed += Placed(ref, (shift.first * 2).roundToInt(), (shift.second * 2).roundToInt())
                }
            }
        } else {
            refs.forEach { placed += Placed(it, 0, 0) }
        }
        if (placed.isEmpty()) return failure("No frame could be read or aligned.")

        // Only the area covered by every frame is kept, so every pixel gets the same frame count.
        val x0 = max(0, placed.maxOf { it.sx })
        val x1 = min(width, width + placed.minOf { it.sx })
        val y0 = max(0, placed.maxOf { it.sy })
        val y1 = min(height, height + placed.minOf { it.sy })
        val iw = x1 - x0
        val ih = y1 - y0
        if (iw <= 0 || ih <= 0) return failure("The frames could not be overlapped. Try fewer frames or a steadier phone.")

        // Pass 2: accumulate at full size.
        val acc = ShortArray(iw * ih * 3)
        val pixels = IntArray(width * height)
        var used = 0
        for ((i, p) in placed.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress("Stacking ${i + 1} of ${placed.size}", 0.3f + 0.6f * i / placed.size)
            val bmp = decode(context, p.ref.location, 1)
            if (bmp == null || bmp.width != width || bmp.height != height) {
                bmp?.recycle()
                rejected++
                continue
            }
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            bmp.recycle()
            for (oy in 0 until ih) {
                val rowBase = (oy + y0 - p.sy) * width + (x0 - p.sx)
                var d = oy * iw * 3
                for (ox in 0 until iw) {
                    val px = pixels[rowBase + ox]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    if (average) {
                        acc[d] = ((acc[d].toInt() and 0xFFFF) + r).toShort()
                        acc[d + 1] = ((acc[d + 1].toInt() and 0xFFFF) + g).toShort()
                        acc[d + 2] = ((acc[d + 2].toInt() and 0xFFFF) + b).toShort()
                    } else {
                        if (r > (acc[d].toInt() and 0xFFFF)) acc[d] = r.toShort()
                        if (g > (acc[d + 1].toInt() and 0xFFFF)) acc[d + 1] = g.toShort()
                        if (b > (acc[d + 2].toInt() and 0xFFFF)) acc[d + 2] = b.toShort()
                    }
                    d += 3
                }
            }
            used++
        }
        if (used == 0) return failure("No frame could be read.")

        onProgress("Finishing", 0.92f)
        val out = IntArray(iw * ih)
        for (i in out.indices) {
            val d = i * 3
            val r: Int
            val g: Int
            val b: Int
            if (average) {
                r = ((acc[d].toInt() and 0xFFFF) + used / 2) / used
                g = ((acc[d + 1].toInt() and 0xFFFF) + used / 2) / used
                b = ((acc[d + 2].toInt() and 0xFFFF) + used / 2) / used
            } else {
                r = acc[d].toInt() and 0xFFFF
                g = acc[d + 1].toInt() and 0xFFFF
                b = acc[d + 2].toInt() and 0xFFFF
            }
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // One stretch, taken from the stack, is applied to the stack and to the single-frame preview.
        val lut = stretch.gamma?.let { buildLut(out, it) }
        if (lut != null) applyLut(out, lut)
        val rotation = exifRotation(context, placed.first().ref.location)

        val fullBitmap = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        fullBitmap.setPixels(out, 0, iw, 0, 0, iw, ih)
        val upright = rotate(fullBitmap, rotation)
        if (upright !== fullBitmap) fullBitmap.recycle()

        val jpeg = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val tag = if (average) "${used}f" else "TRAILS_${used}f"
        val name = "ASTRO_STACK_${session.session.removePrefix("SESSION_")}_$tag.jpg"
        val saved = CaptureStore.saveStacked(context, jpeg, name)

        val stackedPreview = scaleDown(upright)
        if (stackedPreview !== upright) upright.recycle()
        val originalPreview = originalPreview(context, placed.first().ref, lut, rotation)

        val parts = buildList {
            add("Stacked $used of $total frames (${mode.label.lowercase()}).")
            if (note.isNotEmpty()) add(note.trim())
            if (rejected > 0) add("$rejected frames could not be read or aligned.")
            add(if (saved != null) "Saved as $saved in Pictures/AstroCamera/STACKED." else "Could not save the result.")
        }
        return StackResult(originalPreview, stackedPreview, used, rejected, saved, parts.joinToString(" "))
    }

    // ---- decoding ----

    private fun open(context: Context, location: String): InputStream? =
        if (location.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(location))
        else File(location).takeIf { it.exists() }?.inputStream()

    private fun bounds(context: Context, location: String): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, location)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }.getOrNull()

    private fun decode(context: Context, location: String, sample: Int): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        open(context, location)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun exifRotation(context: Context, location: String): Int {
        val orientation = runCatching {
            open(context, location)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull()
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= PREVIEW_MAX) return bitmap
        val scale = PREVIEW_MAX.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
    }

    /** One of the frames with the same stretch as the stack, for a fair side-by-side view. */
    private fun originalPreview(context: Context, ref: FrameRef, lut: IntArray?, rotation: Int): Bitmap? {
        val small = decode(context, ref.location, 4) ?: return null
        if (lut != null) {
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            applyLut(px, lut)
            small.setPixels(px, 0, small.width, 0, 0, small.width, small.height)
        }
        val upright = rotate(small, rotation)
        if (upright !== small) small.recycle()
        return scaleDown(upright)
    }

    // ---- stretch ----

    private fun buildLut(pixels: IntArray, gamma: Float): IntArray {
        val hist = IntArray(256)
        for (p in pixels) {
            val luma = (77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8
            hist[luma]++
        }
        val total = pixels.size
        var lo = 0
        var hi = 255
        var cumulative = 0
        var foundLo = false
        for (v in 0..255) {
            cumulative += hist[v]
            if (!foundLo && cumulative >= total * 0.01) { lo = v; foundLo = true }
            if (cumulative >= total * 0.997) { hi = v; break }
        }
        if (hi < lo + 8) hi = min(255, lo + 8)
        return IntArray(256) { v ->
            val t = ((v - lo) / (hi - lo).toFloat()).coerceIn(0f, 1f)
            (t.pow(gamma) * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    private fun applyLut(pixels: IntArray, lut: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (0xFF shl 24) or
                (lut[(p shr 16) and 255] shl 16) or
                (lut[(p shr 8) and 255] shl 8) or
                lut[p and 255]
        }
    }

    // ---- stars and alignment ----

    private fun detectStars(bitmap: Bitmap): List<Star> {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        bitmap.recycle()
        val luma = FloatArray(w * h) { i ->
            val p = px[i]
            0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var i = 0
        while (i < luma.size) {
            val v = luma[i].toDouble()
            sum += v
            sumSq += v * v
            n++
            i += 7
        }
        val mean = sum / n
        val std = sqrt(max(sumSq / n - mean * mean, 1.0))
        val threshold = min((mean + 4.0 * std).toFloat(), 250f)

        val candidates = ArrayList<Candidate>()
        for (y in 3 until h - 3) {
            for (x in 3 until w - 3) {
                val v = luma[y * w + x]
                if (v <= threshold) continue
                var isPeak = true
                loop@ for (dy in -2..2) {
                    for (dx in -2..2) {
                        if (luma[(y + dy) * w + x + dx] > v) { isPeak = false; break@loop }
                    }
                }
                if (isPeak) candidates += Candidate(x, y, v)
            }
        }
        candidates.sortByDescending { it.v }

        val stars = ArrayList<Star>()
        val accepted = ArrayList<Candidate>()
        for (c in candidates) {
            if (accepted.any { abs(it.x - c.x) < 5 && abs(it.y - c.y) < 5 }) continue
            accepted += c
            var wSum = 0f
            var xSum = 0f
            var ySum = 0f
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val weight = max(0f, luma[(c.y + dy) * w + c.x + dx] - mean.toFloat())
                    wSum += weight
                    xSum += weight * (c.x + dx)
                    ySum += weight * (c.y + dy)
                }
            }
            stars += if (wSum > 0f) Star(xSum / wSum, ySum / wSum) else Star(c.x.toFloat(), c.y.toFloat())
            if (stars.size >= STAR_COUNT) break
        }
        return stars
    }

    /** Votes on the shift that lines up most star pairs. Returns null if no shift is convincing. */
    private fun estimateShift(ref: List<Star>, cur: List<Star>): Pair<Float, Float>? {
        if (ref.size < 5 || cur.size < 5) return null
        val bins = 2 * MAX_SHIFT_HALF + 1
        val hist = IntArray(bins * bins)
        for (r in ref) {
            for (c in cur) {
                val dx = (r.x - c.x).roundToInt()
                val dy = (r.y - c.y).roundToInt()
                if (abs(dx) <= MAX_SHIFT_HALF && abs(dy) <= MAX_SHIFT_HALF) {
                    hist[(dy + MAX_SHIFT_HALF) * bins + dx + MAX_SHIFT_HALF]++
                }
            }
        }
        var best = -1
        var bestScore = 0
        for (y in 1 until bins - 1) {
            for (x in 1 until bins - 1) {
                var s = 0
                for (j in -1..1) for (i in -1..1) s += hist[(y + j) * bins + x + i]
                if (s > bestScore) { bestScore = s; best = y * bins + x }
            }
        }
        if (best < 0 || bestScore < 5) return null
        val bestDx = best % bins - MAX_SHIFT_HALF
        val bestDy = best / bins - MAX_SHIFT_HALF

        var sx = 0f
        var sy = 0f
        var matches = 0
        for (r in ref) {
            for (c in cur) {
                val dx = r.x - c.x
                val dy = r.y - c.y
                if (abs(dx - bestDx) <= 2f && abs(dy - bestDy) <= 2f) {
                    sx += dx
                    sy += dy
                    matches++
                }
            }
        }
        return if (matches >= 5) (sx / matches) to (sy / matches) else null
    }
}
