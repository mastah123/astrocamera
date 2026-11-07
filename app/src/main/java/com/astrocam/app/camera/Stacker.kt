package com.astrocam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
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
 * Inputs can carry weights, so stacked batches combine correctly. [Live] does the same one
 * frame at a time while a burst is still running.
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

        onProgress("Saving and checking", 0.96f)
        return publish(
            context, out, iw, ih, exifRotation(context, placed.first().ref.location), boost, folder,
            session.session.removePrefix("SESSION_"), mode, usedRefs, usedWeight, rejected,
            weights?.sum() ?: total, note, placed.first().ref, previews, true,
        )
    }

    /** Stretch, rotate, save and read back a finished stack. */
    private fun publish(
        context: Context,
        out: IntArray,
        iw: Int,
        ih: Int,
        rotation: Int,
        boost: Boolean,
        folder: String,
        label: String,
        mode: StackMode,
        usedRefs: List<FrameRef>,
        usedWeight: Int,
        rejected: Int,
        totalFrames: Int,
        note: String,
        originalRef: FrameRef?,
        previews: Boolean,
        save: Boolean,
    ): StackResult {
        // One stretch, taken from the stack, is applied to the stack and to the single-frame preview.
        val lut = if (boost) buildLut(out, BOOST_GAMMA) else null
        if (lut != null) applyLut(out, lut)

        val fullBitmap = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        fullBitmap.setPixels(out, 0, iw, 0, 0, iw, ih)
        val upright = rotate(fullBitmap, rotation)
        if (upright !== fullBitmap) fullBitmap.recycle()
        val outW = upright.width
        val outH = upright.height

        var saved: Saved? = null
        if (save) {
            val jpeg = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
            val tag = if (mode == StackMode.Average) "${usedWeight}f" else "TRAILS_${usedWeight}f"
            saved = CaptureStore.saveStacked(context, jpeg, "ASTRO_STACK_${label}_$tag.jpg", folder)
        }
        // The result only counts as safe once it has been read back from storage.
        val location = saved?.location
        val verified = location != null && bounds(context, location)?.let { it.first == outW && it.second == outH } == true

        var stackedPreview: Bitmap? = null
        var originalPreview: Bitmap? = null
        if (previews) {
            stackedPreview = scaleDown(upright)
            if (stackedPreview !== upright) upright.recycle()
            if (originalRef != null) originalPreview = originalPreview(context, originalRef, lut, rotation)
        } else {
            upright.recycle()
        }

        val message = buildList {
            add("Stacked $usedWeight of $totalFrames frames (${mode.label.lowercase()}).")
            if (note.isNotEmpty()) add(note)
            if (rejected > 0) add("$rejected inputs could not be read or aligned.")
            add(
                when {
                    !save -> "Not saved (test)."
                    saved == null -> "Could not save the result."
                    !verified -> "The saved file could not be verified."
                    else -> "Saved as ${saved.name}."
                }
            )
        }.joinToString(" ")
        return StackResult(
            originalPreview, stackedPreview, usedWeight, rejected,
            saved?.name, saved?.location, verified, usedRefs, message,
        )
    }

    /**
     * Stacks frames one at a time as they arrive, on a full-size canvas. Call [add] for every
     * frame in order, then [finish]. Not thread-safe: feed it from one coroutine.
     */
    class Live(private val mode: StackMode) {
        private val average = mode == StackMode.Average
        private var width = 0
        private var height = 0
        private var acc: IntArray? = null
        private var pixels: IntArray? = null
        private var refStars: List<Star>? = null
        private var alignable = true
        private var minSx = 0
        private var maxSx = 0
        private var minSy = 0
        private var maxSy = 0
        private var rotation = 0

        var used = 0
            private set
        var rejected = 0
            private set

        /** True when stars were found in the first frame and later frames are lined up on them. */
        val aligned: Boolean get() = average && refStars != null && alignable

        /** Adds one JPEG. Returns false if it could not be read or lined up. */
        fun add(jpeg: ByteArray): Boolean {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }.getOrNull()
            if (bmp == null) {
                rejected++
                return false
            }
            val w = bmp.width
            val h = bmp.height
            var sums = acc
            var px = pixels
            if (sums == null || px == null) {
                width = w
                height = h
                sums = IntArray(w * h * 3)
                px = IntArray(w * h)
                acc = sums
                pixels = px
                rotation = rotationOf(ByteArrayInputStream(jpeg))
            } else if (w != width || h != height) {
                bmp.recycle()
                rejected++
                return false
            }
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()

            var sx = 0
            var sy = 0
            if (average) {
                val stars = detectStarsFromLuma(halfLuma(px, w, h), w / 2, h / 2)
                val ref = refStars
                if (ref == null) {
                    refStars = stars
                    alignable = stars.size >= 5
                } else if (alignable) {
                    val shift = estimateShift(ref, stars)
                    if (shift == null) {
                        rejected++
                        return false
                    }
                    sx = (shift.first * 2).roundToInt()
                    sy = (shift.second * 2).roundToInt()
                }
            }

            val xStart = max(0, -sx)
            val xEnd = min(w, w - sx)
            val yStart = max(0, -sy)
            val yEnd = min(h, h - sy)
            for (y in yStart until yEnd) {
                var src = y * w + xStart
                var dst = ((y + sy) * w + xStart + sx) * 3
                for (x in xStart until xEnd) {
                    val p = px[src]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (average) {
                        sums[dst] += r
                        sums[dst + 1] += g
                        sums[dst + 2] += b
                    } else {
                        if (r > sums[dst]) sums[dst] = r
                        if (g > sums[dst + 1]) sums[dst + 1] = g
                        if (b > sums[dst + 2]) sums[dst + 2] = b
                    }
                    src++
                    dst += 3
                }
            }
            minSx = min(minSx, sx)
            maxSx = max(maxSx, sx)
            minSy = min(minSy, sy)
            maxSy = max(maxSy, sy)
            used++
            return true
        }

        /** Crops to the area every frame covers, stretches, and saves. [usedRefs] are the saved frames that were added. */
        fun finish(
            context: Context,
            label: String,
            boost: Boolean,
            folder: String,
            previews: Boolean,
            usedRefs: List<FrameRef>,
            save: Boolean = true,
        ): StackResult {
            val sums = acc
            if (sums == null || used == 0) return failure("No frame could be added.")
            val x0 = max(0, maxSx)
            val x1 = min(width, width + minSx)
            val y0 = max(0, maxSy)
            val y1 = min(height, height + minSy)
            val iw = x1 - x0
            val ih = y1 - y0
            if (iw <= 0 || ih <= 0) return failure("The frames could not be overlapped.")
            val out = IntArray(iw * ih)
            for (oy in 0 until ih) {
                var src = ((oy + y0) * width + x0) * 3
                var o = oy * iw
                for (ox in 0 until iw) {
                    val r = if (average) (sums[src] + used / 2) / used else sums[src]
                    val g = if (average) (sums[src + 1] + used / 2) / used else sums[src + 1]
                    val b = if (average) (sums[src + 2] + used / 2) / used else sums[src + 2]
                    out[o] = (0xFF shl 24) or (min(r, 255) shl 16) or (min(g, 255) shl 8) or min(b, 255)
                    src += 3
                    o++
                }
            }
            acc = null
            pixels = null
            val note = if (average && !aligned) "Not enough stars to align, so frames were stacked without alignment." else ""
            return publish(
                context, out, iw, ih, rotation, boost, folder, label, mode, usedRefs, used, rejected,
                used + rejected, note, null, previews, save,
            )
        }
    }

    /**
     * Star trails built from timed shots. Each shot averages a few frames to cut noise, then the
     * brightest pixels of the shot are merged into one running image. Nothing is aligned, so the
     * phone must be on a tripod. Feed it from one coroutine.
     */
    class Trails {
        private var width = 0
        private var height = 0
        private var best: ByteArray? = null
        private var shotSum: ShortArray? = null
        private var pixels: IntArray? = null
        private var rotation = 0
        private var inShot = 0

        var shots = 0
            private set
        var frames = 0
            private set

        /** Adds one frame to the shot being built. Returns false if it could not be read. */
        fun addFrame(jpeg: ByteArray): Boolean {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }.getOrNull() ?: return false
            val w = bmp.width
            val h = bmp.height
            var sum = shotSum
            var px = pixels
            if (sum == null || px == null || best == null) {
                width = w
                height = h
                sum = ShortArray(w * h * 3)
                px = IntArray(w * h)
                shotSum = sum
                pixels = px
                best = ByteArray(w * h * 3)
                rotation = rotationOf(ByteArrayInputStream(jpeg))
            } else if (w != width || h != height) {
                bmp.recycle()
                return false
            }
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            for (i in px.indices) {
                val p = px[i]
                val j = i * 3
                sum[j] = (sum[j] + ((p shr 16) and 0xFF)).toShort()
                sum[j + 1] = (sum[j + 1] + ((p shr 8) and 0xFF)).toShort()
                sum[j + 2] = (sum[j + 2] + (p and 0xFF)).toShort()
            }
            inShot++
            return true
        }

        fun discardShot() {
            shotSum?.fill(0)
            inShot = 0
        }

        /** Averages the frames of this shot and keeps the brightest pixels in the running image. */
        fun endShot() {
            val sum = shotSum ?: return
            val keep = best ?: return
            if (inShot == 0) return
            val n = inShot
            for (i in sum.indices) {
                val v = (sum[i] + n / 2) / n
                if (v > (keep[i].toInt() and 0xFF)) keep[i] = v.toByte()
            }
            sum.fill(0)
            frames += n
            shots++
            inShot = 0
        }

        /** A small, upright picture of the trails so far, cheap enough to make after every shot. */
        fun thumbnail(): Bitmap? {
            val keep = best ?: return null
            val step = 8
            val tw = width / step
            val th = height / step
            if (tw <= 0 || th <= 0) return null
            val px = IntArray(tw * th)
            for (y in 0 until th) {
                for (x in 0 until tw) {
                    val i = ((y * step) * width + x * step) * 3
                    px[y * tw + x] = (0xFF shl 24) or ((keep[i].toInt() and 0xFF) shl 16) or
                        ((keep[i + 1].toInt() and 0xFF) shl 8) or (keep[i + 2].toInt() and 0xFF)
                }
            }
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, tw, 0, 0, tw, th)
            val upright = rotate(bmp, rotation)
            if (upright !== bmp) bmp.recycle()
            return upright
        }

        /** Stretches, saves and reads back the picture so far. Safe to call again for checkpoints. */
        fun render(
            context: Context,
            label: String,
            boost: Boolean,
            folder: String,
            previews: Boolean,
            save: Boolean,
        ): StackResult {
            val keep = best
            if (keep == null || shots == 0) return failure("No shot was captured.")
            val out = IntArray(width * height)
            for (i in out.indices) {
                val j = i * 3
                out[i] = (0xFF shl 24) or ((keep[j].toInt() and 0xFF) shl 16) or
                    ((keep[j + 1].toInt() and 0xFF) shl 8) or (keep[j + 2].toInt() and 0xFF)
            }
            return publish(
                context, out, width, height, rotation, boost, folder, label, StackMode.Trails,
                emptyList(), frames, 0, frames, "", null, previews, save,
            )
        }
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

    private fun rotationOf(stream: InputStream): Int {
        val orientation = runCatching {
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull()
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun exifRotation(context: Context, location: String): Int =
        runCatching { open(context, location)?.use { rotationOf(it) } }.getOrNull() ?: 0

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

    private fun lum(p: Int): Float =
        0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)

    /** Brightness at half size, averaged from 2 x 2 blocks of a full-size frame. */
    private fun halfLuma(px: IntArray, w: Int, h: Int): FloatArray {
        val hw = w / 2
        val hh = h / 2
        val out = FloatArray(hw * hh)
        for (y in 0 until hh) {
            val r0 = 2 * y * w
            val r1 = r0 + w
            for (x in 0 until hw) {
                val i = 2 * x
                out[y * hw + x] = (lum(px[r0 + i]) + lum(px[r0 + i + 1]) + lum(px[r1 + i]) + lum(px[r1 + i + 1])) * 0.25f
            }
        }
        return out
    }

    private fun detectStars(bitmap: Bitmap): List<Star> {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return detectStarsFromLuma(FloatArray(w * h) { lum(px[it]) }, w, h)
    }

    private fun detectStarsFromLuma(luma: FloatArray, w: Int, h: Int): List<Star> {
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
