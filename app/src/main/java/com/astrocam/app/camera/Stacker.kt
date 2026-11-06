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

class StackResult(
    val original: Bitmap?,
    val stacked: Bitmap?,
    /** Frames (or frame-weights) that went into the stack. */
    val used: Int,
    val rejected: Int,
    val savedName: String?,
    val savedLocation: String?,
    /** True only when the saved file was read back and has the expected size. */
    val verified: Boolean,
    /** The inputs that were really used, so only these may be deleted afterwards. */
    val usedRefs: List<FrameRef>,
    val message: String,
)

private class Star(val x: Float, val y: Float)
private class Candidate(val x: Int, val y: Int, val v: Float)
private class Placed(val ref: FrameRef, val sx: Int, val sy: Int, val weight: Int)

/**
 * Aligns frames by matching stars (translation only), then averages them or keeps the
 * brightest pixels. Works in two passes so only the needed frames are decoded at full size.
 * Inputs can carry weights, so stacked batches combine correctly.
 */
object Stacker {
    private const val MAX_FRAMES = 1200
    private const val MAX_SHIFT_HALF = 100
    private const val STAR_COUNT = 60
    private const val PREVIEW_MAX = 1280
    private const val BOOST_GAMMA = 0.6f

    suspend fun stack(
        context: Context,
        session: SessionInfo,
        mode: StackMode,
        boost: Boolean,
        weights: List<Int>? = null,
        folder: String = "AstroCamera/STACKED",
        previews: Boolean = true,
        onProgress: (String, Float) -> Unit,
    ): StackResult = withContext(Dispatchers.Default) {
        run(context, session, mode, boost, weights, folder, previews, onProgress)
    }

    private fun failure(message: String) =
        StackResult(null, null, 0, 0, null, null, false, emptyList(), message)

    private suspend fun run(
        context: Context,
        session: SessionInfo,
        mode: StackMode,
        boost: Boolean,
        weights: List<Int>?,
        folder: String,
        previews: Boolean,
        onProgress: (String, Float) -> Unit,
    ): StackResult {
        val refs = session.frames.take(MAX_FRAMES)
        val total = refs.size
        val (width, height) = refs.firstNotNullOfOrNull { bounds(context, it.location) }
            ?: return failure("Could not read any frame. Were the photos deleted?")
        val average = mode == StackMode.Average
        fun weightOf(index: Int) = weights?.getOrNull(index) ?: 1

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
                    if (!alignable) note = "Not enough stars to align, so frames were stacked without alignment."
                    placed += Placed(ref, 0, 0, weightOf(index))
                } else if (!alignable) {
                    placed += Placed(ref, 0, 0, weightOf(index))
                } else if (size != refHalf) {
                    rejected++
                } else {
                    val shift = estimateShift(refStars, stars)
                    if (shift == null) rejected++
                    else placed += Placed(ref, (shift.first * 2).roundToInt(), (shift.second * 2).roundToInt(), weightOf(index))
                }
            }
        } else {
            refs.forEachIndexed { index, ref -> placed += Placed(ref, 0, 0, weightOf(index)) }
        }
        if (placed.isEmpty()) return failure("No frame could be read or aligned.")

        // Only the area covered by every frame is kept, so every pixel gets the same weight.
        val x0 = max(0, placed.maxOf { it.sx })
        val x1 = min(width, width + placed.minOf { it.sx })
        val y0 = max(0, placed.maxOf { it.sy })
        val y1 = min(height, height + placed.minOf { it.sy })
        val iw = x1 - x0
        val ih = y1 - y0
        if (iw <= 0 || ih <= 0) return failure("The frames could not be overlapped. Try fewer frames or a steadier phone.")

        // Pass 2: accumulate at full size. The sums live inside this function so the memory is
        // freed as soon as the averaged pixels are returned.
        val usedRefs = mutableListOf<FrameRef>()
        var usedWeight = 0
        suspend fun accumulate(): IntArray {
            val acc = IntArray(iw * ih * 3)
            val pixels = IntArray(width * height)
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
                val wgt = p.weight
                for (oy in 0 until ih) {
                    val rowBase = (oy + y0 - p.sy) * width + (x0 - p.sx)
                    var d = oy * iw * 3
                    for (ox in 0 until iw) {
                        val px = pixels[rowBase + ox]
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        if (average) {
                            acc[d] += r * wgt
                            acc[d + 1] += g * wgt
                            acc[d + 2] += b * wgt
                        } else {
                            if (r > acc[d]) acc[d] = r
                            if (g > acc[d + 1]) acc[d + 1] = g
                            if (b > acc[d + 2]) acc[d + 2] = b
                        }
                        d += 3
                    }
                }
                usedRefs += p.ref
                usedWeight += wgt
            }
            if (usedRefs.isEmpty()) return IntArray(0)

            onProgress("Finishing", 0.92f)
            val result = IntArray(iw * ih)
            for (i in result.indices) {
                val d = i * 3
                val r: Int
                val g: Int
                val b: Int
                if (average) {
                    r = (acc[d] + usedWeight / 2) / usedWeight
                    g = (acc[d + 1] + usedWeight / 2) / usedWeight
                    b = (acc[d + 2] + usedWeight / 2) / usedWeight
                } else {
                    r = acc[d]
                    g = acc[d + 1]
                    b = acc[d + 2]
                }
                result[i] = (0xFF shl 24) or (min(r, 255) shl 16) or (min(g, 255) shl 8) or min(b, 255)
            }
            return result
        }
        val out = accumulate()
        if (usedRefs.isEmpty()) return failure("No frame could be read.")

        // One stretch, taken from the stack, is applied to the stack and to the single-frame preview.
        val lut = if (boost) buildLut(out, BOOST_GAMMA) else null
        if (lut != null) applyLut(out, lut)
        val rotation = exifRotation(context, placed.first().ref.location)

        val fullBitmap = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        fullBitmap.setPixels(out, 0, iw, 0, 0, iw, ih)
        val upright = rotate(fullBitmap, rotation)
        if (upright !== fullBitmap) fullBitmap.recycle()
        val outW = upright.width
        val outH = upright.height

        val jpeg = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val tag = if (average) "${usedWeight}f" else "TRAILS_${usedWeight}f"
        val name = "ASTRO_STACK_${session.session.removePrefix("SESSION_")}_$tag.jpg"
        val saved = CaptureStore.saveStacked(context, jpeg, name, folder)
        // The result only counts as safe once it has been read back from storage.
        val verified = saved != null && bounds(context, saved.location)?.let { it.first == outW && it.second == outH } == true

        var stackedPreview: Bitmap? = null
        var originalPreview: Bitmap? = null
        if (previews) {
            stackedPreview = scaleDown(upright)
            if (stackedPreview !== upright) upright.recycle()
            originalPreview = originalPreview(context, placed.first().ref, lut, rotation)
        } else {
            upright.recycle()
        }

        val parts = buildList {
            add("Stacked $usedWeight of ${weights?.sum() ?: total} frames (${mode.label.lowercase()}).")
            if (note.isNotEmpty()) add(note)
            if (rejected > 0) add("$rejected inputs could not be read or aligned.")
            add(
                when {
                    saved == null -> "Could not save the result."
                    !verified -> "The saved file could not be verified."
                    else -> "Saved as ${saved.name}."
                }
            )
        }
        return StackResult(
            originalPreview, stackedPreview, usedWeight, rejected,
            saved?.name, saved?.location, verified, usedRefs, parts.joinToString(" "),
        )
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

    /**
     * One of the frames with the same stretch as the stack, for a fair side-by-side view.
     * Decoded bitmaps are read-only, so the pixels go into a new bitmap instead of being edited in place.
     */
    private fun originalPreview(context: Context, ref: FrameRef, lut: IntArray?, rotation: Int): Bitmap? {
        val small = decode(context, ref.location, 4) ?: return null
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        if (lut != null) applyLut(px, lut)
        val editable = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        editable.setPixels(px, 0, w, 0, 0, w, h)
        val upright = rotate(editable, rotation)
        if (upright !== editable) editable.recycle()
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
