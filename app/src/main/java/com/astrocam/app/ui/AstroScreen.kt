package com.astrocam.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.TextureView
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.astrocam.app.camera.AppSettings
import com.astrocam.app.camera.CameraInfo
import com.astrocam.app.camera.CaptureStore
import com.astrocam.app.camera.ControlLimits
import com.astrocam.app.camera.CrashLog
import com.astrocam.app.camera.FrameRef
import com.astrocam.app.camera.LiveReading
import com.astrocam.app.camera.ManualSettings
import com.astrocam.app.camera.Originals
import com.astrocam.app.camera.OriginalsPolicy
import com.astrocam.app.camera.PreviewController
import com.astrocam.app.camera.ProgressTracker
import com.astrocam.app.camera.formatProgress
import com.astrocam.app.camera.RecentCapture
import com.astrocam.app.camera.SessionInfo
import com.astrocam.app.camera.StackMode
import com.astrocam.app.camera.StackPlan
import com.astrocam.app.camera.StackResult
import com.astrocam.app.camera.Stacker
import com.astrocam.app.camera.Thermal
import com.astrocam.app.camera.ThermalLevel
import com.astrocam.app.camera.exposureAt
import com.astrocam.app.camera.formatExposure
import com.astrocam.app.camera.isoAt
import com.astrocam.app.camera.isoFraction
import com.astrocam.app.camera.previewConfig
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Control { Exposure, Iso, Focus, Frames, Timer, Stack, TrailFrames, Every, Duration }

private val Control.isSetting get() = this == Control.Frames || this == Control.Timer || this == Control.Stack ||
    this == Control.TrailFrames || this == Control.Every || this == Control.Duration

private const val MAX_FRAMES_UI = 1000
private const val MAX_TIMER = 30
private val FRAME_SNAPS = listOf(1, 5, 10, 20, 50, 100, 200, 500, 1000)
private val TIMER_SNAPS = listOf(0, 2, 5, 10, 20, 30)
private val STACK_STOPS = listOf(-1, 0, 3, 5, 10, 20, 50, 100, 200)
private val TRAIL_FRAME_STOPS = listOf(1, 2, 3, 4, 5)
private val TRAIL_EVERY_STOPS = listOf(0, 5, 10, 15, 30, 60, 120, 300)
private val TRAIL_MINUTE_STOPS = listOf(5, 10, 15, 30, 60, 90, 120, 180, 240)

private fun idxFrac(stops: List<Int>, value: Int): Float {
    val i = stops.indices.minByOrNull { abs(stops[it] - value) } ?: 0
    return i / (stops.size - 1).toFloat()
}
private fun idxAt(stops: List<Int>, f: Float) = stops[(f.coerceIn(0f, 1f) * (stops.size - 1)).roundToInt()]
private fun idxSnaps(stops: List<Int>, label: (Int) -> String) =
    stops.mapIndexed { i, v -> Snap(i / (stops.size - 1).toFloat(), label(v)) }

private fun intervalLabel(seconds: Int) = when {
    seconds == 0 -> "Continuous"
    seconds < 60 -> "$seconds s"
    else -> "${seconds / 60} min"
}
private fun intervalShort(seconds: Int) = when {
    seconds == 0 -> "Cont"
    seconds < 60 -> "${seconds}s"
    else -> "${seconds / 60}m"
}
private fun minutesLabel(m: Int) = if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 != 0) " ${m % 60} min" else ""
private fun minutesShort(m: Int) = if (m < 60) "${m}m" else if (m % 60 == 0) "${m / 60}h" else "%.1fh".format(Locale.US, m / 60.0)

private fun formatClock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
}

/** How many pixels a star drifts across the picture each second (at the celestial equator, the fastest). */
private fun pxPerSecond(info: CameraInfo): Double {
    val physical = info.physicalWidthMm
    val focal = info.focalLengthsMm.firstOrNull()
    if (physical == null || focal == null || focal <= 0f || info.sensorWidth <= 0) return 0.2
    val degreesPerPixel = Math.toDegrees(atan(physical.toDouble() / info.sensorWidth / focal))
    return 0.004167 / degreesPerPixel
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private val EXPOSURE_STOPS = listOf(
    1_000_000L to "1/1000", 4_000_000L to "1/250", 16_666_667L to "1/60", 33_333_333L to "1/30",
    66_666_667L to "1/15", 125_000_000L to "1/8", 250_000_000L to "1/4", 500_000_000L to "1/2",
    1_000_000_000L to "1s", 2_000_000_000L to "2s", 4_000_000_000L to "4s", 8_000_000_000L to "8s",
    15_000_000_000L to "15s", 30_000_000_000L to "30s",
)
private val ISO_STOPS = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800)
private val FOCUS_STOPS = listOf(0f to "∞", 0.33f to "3m", 1f to "1m", 2f to "50cm", 4f to "25cm")

private fun framesFrac(n: Int) = (ln(n.coerceIn(1, MAX_FRAMES_UI).toDouble()) / ln(MAX_FRAMES_UI.toDouble())).toFloat()
private fun framesAt(f: Float) =
    exp(f.coerceIn(0f, 1f) * ln(MAX_FRAMES_UI.toDouble())).roundToInt().coerceIn(1, MAX_FRAMES_UI)
private fun timerFrac(s: Int) = s / MAX_TIMER.toFloat()
private fun timerAt(f: Float) = (f.coerceIn(0f, 1f) * MAX_TIMER).roundToInt()
private fun stackIndex(batch: Int) = STACK_STOPS.indices.minByOrNull { abs(STACK_STOPS[it] - batch) } ?: 0
private fun stackFrac(batch: Int) = stackIndex(batch) / (STACK_STOPS.size - 1).toFloat()
private fun stackAt(f: Float) = STACK_STOPS[(f.coerceIn(0f, 1f) * (STACK_STOPS.size - 1)).roundToInt()]

/** Keeps ruler labels from overlapping by dropping marks that sit too close to the previous one. */
private fun thin(snaps: List<Snap>, gap: Float = 0.09f): List<Snap> {
    val kept = mutableListOf<Snap>()
    snaps.sortedBy { it.frac }.forEach { s -> if (kept.isEmpty() || s.frac - kept.last().frac >= gap) kept += s }
    return kept
}

private fun exposureSnaps(limits: ControlLimits): List<Snap> {
    if (limits.maxExposureNs <= limits.minExposureNs) return emptyList()
    val lo = ln(limits.minExposureNs.toDouble())
    val hi = ln(limits.maxExposureNs.toDouble())
    val marks = EXPOSURE_STOPS.filter { it.first in limits.minExposureNs..limits.maxExposureNs }
        .map { Snap(((ln(it.first.toDouble()) - lo) / (hi - lo)).toFloat(), it.second) }
    return thin(marks + Snap(1f, formatExposure(limits.maxExposureNs).replace(" ", "")))
}

private fun isoSnaps(limits: ControlLimits): List<Snap> {
    val marks = ISO_STOPS.filter { it in limits.isoMin..limits.isoMax }.map { Snap(limits.isoFraction(it), it.toString()) }
    return thin(marks + Snap(1f, limits.isoMax.toString()))
}

private fun focusSnaps(limits: ControlLimits): List<Snap> {
    if (limits.minFocusDiopters <= 0f) return emptyList()
    return thin(FOCUS_STOPS.filter { it.first <= limits.minFocusDiopters }.map { Snap(it.first / limits.minFocusDiopters, it.second) })
}

private fun formatTotal(ns: Long): String {
    val seconds = ns / 1e9
    return if (seconds < 60) "%.1f s".format(Locale.US, seconds) else "%.1f min".format(Locale.US, seconds / 60)
}

private fun formatStorage(megabytes: Double): String =
    if (megabytes >= 1000) "%.1f GB".format(Locale.US, megabytes / 1000) else "%.0f MB".format(Locale.US, megabytes)

private fun vibrate(context: Context) {
    runCatching {
        context.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

private fun makeThumb(jpeg: ByteArray, rotation: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inSampleSize = 8 }
    val bitmap = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }.getOrNull() ?: return null
    if (rotation == 0) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
}

// Brightens only what you see in the viewfinder, so you can aim in the dark. Photos are not changed.
private const val PREVIEW_GAIN = 6f
private const val PREVIEW_BLACK = 6f

private fun applyPreviewBoost(view: TextureView, on: Boolean) {
    if (on) {
        val offset = -PREVIEW_GAIN * PREVIEW_BLACK
        val matrix = ColorMatrix(
            floatArrayOf(
                PREVIEW_GAIN, 0f, 0f, 0f, offset,
                0f, PREVIEW_GAIN, 0f, 0f, offset,
                0f, 0f, PREVIEW_GAIN, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
    } else {
        view.setLayerType(View.LAYER_TYPE_NONE, null)
    }
}

@Composable
private fun BoostPill(on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Text(
        if (on) "☀ Boost on" else "☀ Boost",
        style = MaterialTheme.typography.labelLarge,
        color = if (on) colors.onPrimary else colors.onBackground,
        modifier = modifier
            .padding(8.dp)
            .clip(shape)
            .background(if (on) colors.primary else Color.Black.copy(alpha = 0.55f))
            .border(1.dp, if (on) colors.primary else colors.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun AstroScreen(
    limits: ControlLimits?,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    startInTrails: Boolean,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.background) { padding ->
        if (limits == null) {
            Column(Modifier.padding(padding).padding(20.dp)) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("No rear camera was found on this device.")
            }
        } else {
            AstroContent(limits, settings, onSettings, startInTrails, onBack, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AstroContent(
    limits: ControlLimits,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    startInTrails: Boolean,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val cameraId = limits.camera.id

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) { if (!hasPermission) permission.launch(Manifest.permission.CAMERA) }

    val config = remember(cameraId) { previewConfig(context, cameraId) }
    var reading by remember { mutableStateOf<LiveReading?>(null) }
    val controller = remember { PreviewController(context) { reading = it } }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var resumed by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var canCapture by remember { mutableStateOf(false) }

    // The camera opens in Auto. Manual controls are one tap away.
    var manual by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(Control.Exposure) }
    var panelOpen by remember { mutableStateOf(false) }
    var exposureFrac by remember { mutableStateOf(1f) }
    var isoFrac by remember { mutableStateOf(limits.isoFraction(800)) }
    var focusFrac by remember { mutableStateOf(0f) }
    var autoFocus by remember { mutableStateOf(false) }
    val trails = startInTrails

    var countdown by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // Only problems and progress appear in the details. Saved file names and results do not.
    var status by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var frameIndex by remember { mutableStateOf(0) }
    var frameTotal by remember { mutableStateOf(1) }
    var savedCount by remember { mutableStateOf(0) }
    var liveDone by remember { mutableStateOf(0) }
    var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
    var lastLocation by remember { mutableStateOf<String?>(null) }
    var stackFraction by remember { mutableStateOf<Float?>(null) }
    var stackDone by remember { mutableStateOf(false) }
    var trailRun by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    val tracker = remember { ProgressTracker() }
    var askDialog by remember { mutableStateOf(false) }
    var tempDialog by remember { mutableStateOf(false) }
    var resumePrompt by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val thermal by rememberThermal()
    val level = Thermal.level(thermal, settings.tempPreset)

    val ms = ManualSettings(
        manual = manual && limits.manualAvailable,
        exposureNs = limits.exposureAt(exposureFrac),
        iso = limits.isoAt(isoFrac),
        focusDiopters = focusFrac * limits.minFocusDiopters,
        autoFocus = autoFocus,
    )
    val latestManual by rememberUpdatedState(ms)
    val latestSettings by rememberUpdatedState(settings)
    val latestTrails by rememberUpdatedState(trails)

    val exposureSnaps = remember(limits) { exposureSnaps(limits) }
    val isoSnaps = remember(limits) { isoSnaps(limits) }
    val focusSnaps = remember(limits) { focusSnaps(limits) }
    val frameSnaps = remember { thin(FRAME_SNAPS.map { Snap(framesFrac(it), it.toString()) }, 0.08f) }
    val timerSnaps = remember { TIMER_SNAPS.map { Snap(timerFrac(it), if (it == 0) "Off" else "${it}s") } }
    val stackSnaps = remember {
        STACK_STOPS.map {
            Snap(stackFrac(it), when { it < 0 -> "Off"; it == 0 -> "After"; else -> it.toString() })
        }
    }

    // Star trails: many frames at the longest exposure, brightest pixels kept.
    fun enterTrails() {
        if (limits.manualAvailable) manual = true
        exposureFrac = 1f
        isoFrac = limits.isoFraction(800)
        focusFrac = 0f
        autoFocus = false
        active = Control.Every
        panelOpen = true
    }
    LaunchedEffect(Unit) {
        if (startInTrails) enterTrails()
        // Show the newest photo next to the shutter when the camera opens.
        val recent = withContext(Dispatchers.IO) { RecentCapture.latest(context) }
        if (recent != null && lastThumb == null) {
            lastThumb = recent.thumb
            lastLocation = recent.uri
        }
    }
    // A warning goes away by itself after a few seconds.
    LaunchedEffect(status, busy) {
        if (status != null && !busy) {
            delay(8000)
            status = null
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { resumed = false; canCapture = false; controller.stop() }
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    // Coming back from the gallery: show the normal details again.
                    if (!busy) status = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val surfaceTexture = texture
    LaunchedEffect(surfaceTexture, hasPermission, resumed) {
        if (surfaceTexture != null && hasPermission && resumed && config != null) {
            error = controller.start(cameraId, surfaceTexture, config.size, config.jpegSize, ms)
            canCapture = error == null && controller.canCapture
        }
    }
    LaunchedEffect(ms) { controller.update(ms) }

    fun shoot(deleteOriginals: Boolean) {
        val cfg = config ?: return
        job = scope.launch {
            busy = true
            status = null
            phase = ""
            savedCount = 0
            liveDone = 0
            stackFraction = null
            frameIndex = 0
            val app = latestSettings
            frameTotal = app.frameCount
            val method = StackMode.Average
            val live = if (app.liveStacking && app.stackBatch >= 0 && app.frameCount > 1) Stacker.Live(method) else null
            val liveChannel = Channel<Pair<Int, ByteArray>>(2)
            val liveAdded = Collections.synchronizedSet(mutableSetOf<Int>())
            var liveJob: Job? = null
            val pending = ArrayDeque<Deferred<FrameRef?>>()
            try {
                var remaining = app.timerSeconds
                while (remaining > 0) {
                    countdown = remaining
                    delay(1000)
                    remaining--
                }
                countdown = 0

                // Every frame uses the same settings, taken when the sequence starts.
                val used = latestManual
                val total = app.frameCount
                val batch = if (total > 1) app.stackBatch else -1
                val session = if (total > 1) CaptureStore.newSession() else null
                val minimal = total > 1 && used.manual
                val savedFrames = mutableListOf<FrameRef>()
                val batchFrames = mutableListOf<FrameRef>()
                val batchImages = mutableListOf<FrameRef>()
                val batchWeights = mutableListOf<Int>()
                var batchNo = 0
                var batchFailed = 0
                var deleted = 0
                var failures = 0
                var stopReason: String? = null

                if (live != null) {
                    // Live stacking: frames are aligned and added one at a time while the next is captured.
                    liveJob = scope.launch(Dispatchers.Default) {
                        for ((index, bytes) in liveChannel) {
                            if (live.add(bytes)) liveAdded += index
                            liveDone++
                        }
                    }
                }

                suspend fun reap() {
                    val ref = pending.removeFirst().await()
                    if (ref != null) {
                        savedCount++
                        savedFrames += ref
                        batchFrames += ref
                        lastLocation = ref.location
                    }
                }

                suspend fun drain() {
                    while (pending.isNotEmpty()) reap()
                }

                suspend fun stackBatch() {
                    if (batchFrames.isEmpty()) return
                    batchNo++
                    val refs = batchFrames.toList()
                    batchFrames.clear()
                    phase = "Batch $batchNo · starting…"
                    val info = SessionInfo(
                        "${session}_B%02d".format(Locale.US, batchNo), refs, used.exposureNs, used.iso, used.manual,
                    )
                    val batchCount = max(1, (total + batch - 1) / max(1, batch))
                    val r = try {
                        Stacker.stack(context, info, method, false, folder = "AstroCamera/STACKED/BATCHES", previews = false) { _, f ->
                            stackFraction = (batchNo - 1 + f) / (batchCount + 1f)
                            phase = "Batch $batchNo of $batchCount · ${(f * 100).roundToInt()}%"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        CrashLog.record(context, e)
                        null
                    }
                    val location = r?.savedLocation
                    val name = r?.savedName
                    if (r != null && r.verified && location != null && name != null) {
                        batchImages += FrameRef(name, location, batchNo)
                        batchWeights += r.used
                        if (deleteOriginals) deleted += Originals.delete(context, r.usedRefs)
                    } else {
                        batchFailed++
                    }
                    phase = ""
                }

                for (i in 1..total) {
                    frameIndex = i
                    val t = Thermal.read(context)
                    if (app.tempProtection) {
                        var lvl = Thermal.level(t, app.tempPreset)
                        if (lvl == ThermalLevel.Stop) {
                            stopReason = "Phone reached ${Thermal.tempText(t)}, so the burst was stopped."
                            break
                        }
                        if (lvl == ThermalLevel.Hot) {
                            vibrate(context)
                            drain()
                            phase = "Phone is hot. Pausing to cool down…"
                            var waited = 0
                            while (waited < 600_000) {
                                delay(5_000)
                                waited += 5_000
                                lvl = Thermal.level(Thermal.read(context), app.tempPreset, margin = 2f)
                                if (lvl < ThermalLevel.Hot) break
                            }
                            phase = ""
                            if (lvl >= ThermalLevel.Hot) {
                                stopReason = "Phone stayed too hot, so the burst was stopped."
                                break
                            }
                            val ask = CompletableDeferred<Boolean>()
                            resumePrompt = ask
                            val resume = ask.await()
                            resumePrompt = null
                            if (!resume) {
                                stopReason = "Stopped at your request after cooling."
                                break
                            }
                        }
                    }

                    val outcome = controller.capture(used, cfg.sensorOrientation, minimal)
                    if (outcome == null) {
                        failures++
                        if (failures >= 3) {
                            stopReason = "Stopped after repeated capture failures."
                            break
                        }
                        continue
                    }
                    scope.launch(Dispatchers.Default) { lastThumb = makeThumb(outcome.jpeg, cfg.sensorOrientation) ?: lastThumb }
                    // Save in the background while the next frame is being captured.
                    pending.addLast(scope.async(Dispatchers.IO) {
                        CaptureStore.save(context, outcome.jpeg, used, outcome.reading, cameraId, session, i, total, t)
                            ?.let { FrameRef(it.name, it.location, i) }
                    })
                    // If stacking falls behind, this waits instead of dropping frames.
                    if (live != null) liveChannel.send(i to outcome.jpeg)
                    if (pending.size >= 3) reap()
                    if (live == null && batch > 0 && i % batch == 0) {
                        drain()
                        stackBatch()
                    }
                }
                drain()
                if (live != null) {
                    liveChannel.close()
                    phase = "Finishing live stack…"
                    liveJob?.join()
                }

                var finalResult: StackResult? = null
                if (batch >= 0 && savedFrames.isNotEmpty() && session != null) {
                    try {
                        if (live != null) {
                            phase = "Saving the live stack…"
                            val addedRefs = savedFrames.filter { it.frame in liveAdded }
                            finalResult = withContext(Dispatchers.Default) {
                                live.finish(context, session.removePrefix("SESSION_"), app.boost, "AstroCamera/STACKED", true, addedRefs)
                            }
                        } else if (batch == 0) {
                            phase = "Stacking ${savedFrames.size} frames…"
                            val info = SessionInfo(session, savedFrames.toList(), used.exposureNs, used.iso, used.manual)
                            tracker.begin()
                            finalResult = Stacker.stack(context, info, method, app.boost) { label, f ->
                                stackFraction = f
                                phase = formatProgress(label, f, tracker.etaSeconds(f))
                            }
                        } else {
                            stackBatch()
                            if (batchImages.isNotEmpty()) {
                                phase = "Combining ${batchImages.size} batches…"
                                val info = SessionInfo(session, batchImages.toList(), used.exposureNs, used.iso, used.manual)
                                tracker.begin()
                                val batchCount = batchImages.size + batchFailed
                                finalResult = Stacker.stack(
                                    context, info, method, app.boost, weights = batchWeights.toList(),
                                ) { label, f ->
                                    stackFraction = (batchCount + f) / (batchCount + 1f)
                                    phase = formatProgress(label, f, tracker.etaSeconds(f))
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        CrashLog.record(context, e)
                    }
                }
                val result = finalResult
                if (result != null && result.verified && deleteOriginals) {
                    deleted += Originals.delete(context, result.usedRefs)
                }
                if (result != null && result.verified) {
                    result.stacked?.let { lastThumb = it }
                    result.savedLocation?.let { lastLocation = it }
                }

                // Only problems are shown. A normal finish just updates the thumbnail.
                val problems = buildString {
                    stopReason?.let { append(it).append(' ') }
                    if (total == 1 && savedFrames.isEmpty()) append("Capture failed. Try again. ")
                    else if (batch >= 0 && !(result != null && result.verified)) {
                        append("Stacking did not finish, so the originals were kept. ")
                    }
                    if (batchFailed > 0) append("$batchFailed batch(es) could not be stacked; their frames were kept.")
                }.trim()
                status = problems.ifEmpty { null }
                if (batch >= 0 && result != null && result.verified) {
                    stackDone = true
                    vibrate(context)
                    scope.launch {
                        delay(2500)
                        stackDone = false
                    }
                }
            } finally {
                liveChannel.close()
                liveJob?.cancel()
                stackFraction = null
                busy = false
                countdown = 0
                phase = ""
                resumePrompt = null
            }
        }
    }

    fun shootTrails(deleteOriginals: Boolean) {
        val cfg = config ?: return
        job = scope.launch {
            busy = true
            trailRun = true
            stopRequested = false
            status = null
            phase = ""
            savedCount = 0
            frameIndex = 0
            frameTotal = 1
            stackFraction = null
            val app = latestSettings
            val used = latestManual
            val engine = Stacker.Trails()
            val session = CaptureStore.newSession()
            val label = session.removePrefix("SESSION_")
            val keepFrames = !deleteOriginals
            val pending = ArrayDeque<Deferred<FrameRef?>>()
            val window = view.context.findActivity()?.window
            val lights = window?.attributes
            var lastCheckpoint: FrameRef? = null
            var shotNo = 0
            var failures = 0
            var stopReason: String? = null
            try {
                // Dim the screen for a long session. It is restored at the end.
                if (window != null && lights != null) {
                    lights.screenBrightness = 0.02f
                    window.attributes = lights
                }
                for (second in 5 downTo 1) {
                    if (stopRequested) break
                    countdown = second
                    delay(1000)
                }
                countdown = 0

                val durationMs = app.trailMinutes * 60_000L
                val intervalMs = app.trailInterval * 1000L
                val sessionStart = SystemClock.elapsedRealtime()
                fun elapsed() = SystemClock.elapsedRealtime() - sessionStart
                fun timeLeft() = formatClock(max(0L, durationMs - elapsed()) / 1000)

                suspend fun reap() {
                    val ref = pending.removeFirst().await()
                    if (ref != null) savedCount++
                }

                while (!stopRequested && elapsed() < durationMs) {
                    val cycleStart = SystemClock.elapsedRealtime()
                    val t = Thermal.read(context)
                    if (app.tempProtection) {
                        var lvl = Thermal.level(t, app.tempPreset)
                        if (lvl == ThermalLevel.Stop) {
                            stopReason = "Phone reached ${Thermal.tempText(t)}, so the session was ended."
                            break
                        }
                        if (lvl == ThermalLevel.Hot) {
                            vibrate(context)
                            var waited = 0
                            while (waited < 600_000 && !stopRequested) {
                                phase = "Phone is hot. Pausing to cool down…"
                                delay(5_000)
                                waited += 5_000
                                lvl = Thermal.level(Thermal.read(context), app.tempPreset, margin = 2f)
                                if (lvl < ThermalLevel.Hot) break
                            }
                            phase = ""
                            if (lvl >= ThermalLevel.Hot) {
                                stopReason = "Phone stayed too hot, so the session was ended."
                                break
                            }
                            continue
                        }
                    }

                    shotNo++
                    frameIndex = shotNo
                    phase = "Shot $shotNo · capturing · ${timeLeft()} left"
                    var got = 0
                    for (k in 1..app.trailFrames) {
                        val outcome = controller.capture(used, cfg.sensorOrientation, true)
                        if (outcome == null) {
                            failures++
                            if (failures >= 5) break
                            continue
                        }
                        if (engine.addFrame(outcome.jpeg)) got++
                        if (keepFrames) {
                            val index = shotNo * 100 + k
                            pending.addLast(scope.async(Dispatchers.IO) {
                                CaptureStore.save(context, outcome.jpeg, used, outcome.reading, cameraId, session, index, 0, t)
                                    ?.let { FrameRef(it.name, it.location, index) }
                            })
                            if (pending.size >= 3) reap()
                        }
                    }
                    if (failures >= 5) {
                        stopReason = "Stopped after repeated capture failures."
                        break
                    }
                    if (got > 0) engine.endShot() else engine.discardShot()
                    engine.thumbnail()?.let { lastThumb = it }

                    // A checkpoint every 10 shots means a crash costs minutes, not the whole night.
                    if (shotNo % 10 == 0) {
                        phase = "Saving a checkpoint…"
                        val r = withContext(Dispatchers.Default) {
                            engine.render(context, "${label}_CHK", app.boost, "AstroCamera/STACKED", true, true)
                        }
                        if (r.verified) {
                            lastCheckpoint?.let { Originals.delete(context, listOf(it)) }
                            val name = r.savedName
                            val location = r.savedLocation
                            if (name != null && location != null) {
                                lastCheckpoint = FrameRef(name, location, shotNo)
                                lastLocation = location
                            }
                            r.stacked?.let { lastThumb = it }
                        }
                    }

                    // Wait out the rest of the interval.
                    var left = intervalMs - (SystemClock.elapsedRealtime() - cycleStart)
                    while (left > 0 && !stopRequested && elapsed() < durationMs) {
                        phase = "Shot $shotNo done · next in ${(left + 999) / 1000} s · ${timeLeft()} left"
                        stackFraction = (elapsed().toFloat() / durationMs).coerceIn(0f, 1f)
                        delay(1000)
                        left -= 1000
                    }
                    stackFraction = (elapsed().toFloat() / durationMs).coerceIn(0f, 1f)
                }
                while (pending.isNotEmpty()) reap()

                phase = "Saving the star trails…"
                var finished: StackResult? = null
                if (engine.shots > 0) {
                    finished = withContext(Dispatchers.Default) {
                        engine.render(context, label, app.boost, "AstroCamera/STACKED", true, true)
                    }
                }
                val result = finished
                if (result != null && result.verified) {
                    lastCheckpoint?.let { Originals.delete(context, listOf(it)) }
                    result.stacked?.let { lastThumb = it }
                    result.savedLocation?.let { lastLocation = it }
                    stackDone = true
                    vibrate(context)
                    scope.launch {
                        delay(2500)
                        stackDone = false
                    }
                }
                status = buildString {
                    stopReason?.let { append(it).append(' ') }
                    if (engine.shots == 0) append("No shot was captured. ")
                    else if (result == null || !result.verified) {
                        append("The star trails image could not be saved and checked. A checkpoint may be in the gallery. ")
                    }
                }.trim().ifEmpty { null }
            } finally {
                if (window != null && lights != null) {
                    lights.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lights
                }
                stackFraction = null
                trailRun = false
                stopRequested = false
                busy = false
                countdown = 0
                phase = ""
            }
        }
    }

    // One place that starts whichever kind of session the screen is in.
    fun begin(deleteOriginals: Boolean) {
        if (latestTrails) shootTrails(deleteOriginals) else shoot(deleteOriginals)
    }

    val focusText = when {
        autoFocus -> "AF"
        focusFrac < 0.01f -> "∞"
        else -> "%.0f cm".format(Locale.US, 100f / (focusFrac * limits.minFocusDiopters))
    }
    val exposureText = formatExposure(ms.exposureNs)
    val readout = (if (trails) "★ " else "") +
        if (ms.manual) "$exposureText · ISO ${ms.iso}" + (if (limits.manualFocus) " · $focusText" else "") else "Auto"
    val burst = busy && frameTotal > 1 && countdown == 0
    val showPanel = panelOpen && (active.isSetting || (ms.manual && (active != Control.Focus || limits.manualFocus)))
    val megapixels = config?.jpegSize?.let { it.width * it.height / 1e6 } ?: 8.0
    val sizeEach = megapixels * 0.4
    val totalText = formatTotal(ms.exposureNs * settings.frameCount)

    // Details under the top bar: settings with estimated time and size, in Auto and in Manual.
    val details = buildString {
        if (ms.manual) {
            append("$exposureText · ISO ${ms.iso}")
        } else {
            append("Auto")
            reading?.let { append(" · ${it.exposureNs?.let(::formatExposure) ?: "n/a"} · ISO ${it.iso ?: "n/a"}") }
        }
        val perFrameNs = if (ms.manual) ms.exposureNs else (reading?.frameNs ?: reading?.exposureNs)
        if (settings.frameCount > 1) {
            append(" · ${settings.frameCount} frames")
            perFrameNs?.let { append(" · ≈ ${formatTotal(it * settings.frameCount)}") }
            append(" · ≈ ${formatStorage(settings.frameCount * sizeEach)}")
        } else {
            append(" · ≈ ${formatStorage(sizeEach)}")
        }
        if (settings.timerSeconds > 0) append(" · ${settings.timerSeconds}s timer")
        if (settings.boost) append(" · boost")
        if (settings.frameCount > 1 && settings.stackBatch >= 0) {
            append(" · stack ${StackPlan.short(settings.stackBatch)}")
            if (settings.liveStacking) append(" (live)")
            append(if (settings.deleteOriginals) " · delete originals" else " · keep originals")
        }
    }

    val trailK = settings.trailFrames
    val trailShotMs = trailK * (ms.exposureNs / 1_000_000L) + 1500L
    val trailCycleMs = max(settings.trailInterval * 1000L, trailShotMs)
    val trailSeconds = settings.trailMinutes * 60L
    val trailShots = (trailSeconds * 1000L / trailCycleMs).toInt()
    val pxPerSec = pxPerSecond(limits.camera)
    val gapPx = max(0.0, (trailCycleMs - trailK * (ms.exposureNs / 1_000_000L)) / 1000.0 * pxPerSec)
    val trailsDetails = buildString {
        append("★ $trailShots shots · $trailK frames each · ")
        append(if (settings.trailInterval == 0) "continuous" else "every ${settings.trailInterval} s")
        append(" · ${minutesLabel(settings.trailMinutes)}")
        append(" · trails ≈ ${(trailSeconds * pxPerSec).roundToInt()} px")
        append(if (gapPx <= 2.0) " · no gaps" else " · gaps ≈ ${gapPx.roundToInt()} px")
        append(
            if (settings.deleteOriginals) " · originals not kept"
            else " · originals ≈ ${formatStorage(trailShots * trailK * sizeEach)}"
        )
    }

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Top bar: back, current settings, star trails, heat.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, enabled = !busy) { Text("‹ Back") }
            Text(readout, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium)
            if (trails) {
                Text("★ Trails", style = MaterialTheme.typography.labelLarge, color = colors.primary,
                    modifier = Modifier.padding(horizontal = 6.dp))
            }
            TempChip(thermal, level) { tempDialog = true }
            Spacer(Modifier.width(8.dp))
        }

        // Details sit right under the Back button.
        Text(
            when {
                !hasPermission -> "Camera permission is needed for the preview."
                error != null -> "Preview failed: $error"
                phase.isNotEmpty() -> phase
                burst && settings.liveStacking && settings.stackBatch >= 0 ->
                    "Frame $frameIndex of $frameTotal · $liveDone stacked"
                burst -> "Frame $frameIndex of $frameTotal · saved $savedCount"
                status != null -> status.orEmpty()
                else -> if (trails) trailsDetails else details
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        )
        if (!hasPermission) {
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Grant access") }
        }

        // Preview, with the open control's slider laid over its lower edge.
        if (config != null) {
            Box(Modifier.weight(1f, fill = false).aspectRatio(config.aspect)) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    texture = st
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    controller.stop()
                                    texture = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { tv -> applyPreviewBoost(tv, settings.boost) },
                )
                BoostPill(settings.boost, Modifier.align(Alignment.TopEnd)) {
                    onSettings(settings.copy(boost = !settings.boost))
                }
                if (showPanel) {
                    val at = Modifier.align(Alignment.BottomCenter)
                    when (active) {
                        Control.Exposure -> SliderPanel(
                            "Exposure", exposureText, exposureFrac, { exposureFrac = it }, exposureSnaps,
                            if (limits.exposureVerified) "Verified max ${formatExposure(limits.maxExposureNs)}"
                            else "Unverified max ${formatExposure(limits.maxExposureNs)}. Run the Long Exposure Test.",
                            at,
                        )
                        Control.Iso -> SliderPanel(
                            "ISO", ms.iso.toString(), isoFrac, { isoFrac = it }, isoSnaps,
                            "Range as reported by the phone", at,
                        )
                        Control.Focus -> SliderPanel(
                            "Focus", focusText, focusFrac, { focusFrac = it; autoFocus = false }, focusSnaps,
                            "Reported by the phone, not yet verified. Left end is infinity.", at,
                            extra = {
                                TextButton(onClick = { autoFocus = true }) { Text(if (autoFocus) "● AF" else "AF") }
                                TextButton(onClick = { autoFocus = false; focusFrac = 0f }) { Text("∞") }
                            },
                        )
                        Control.Frames -> SliderPanel(
                            "Frames", "${settings.frameCount}", framesFrac(settings.frameCount),
                            { onSettings(settings.copy(frameCount = framesAt(it))) }, frameSnaps,
                            if (ms.manual) "${settings.frameCount} × $exposureText = $totalText · about ${formatStorage(settings.frameCount * sizeEach)} if all kept"
                            else "About ${formatStorage(settings.frameCount * sizeEach)} if all frames are kept",
                            at,
                        )
                        Control.Timer -> SliderPanel(
                            "Timer", if (settings.timerSeconds == 0) "Off" else "${settings.timerSeconds} s",
                            timerFrac(settings.timerSeconds), { onSettings(settings.copy(timerSeconds = timerAt(it))) },
                            timerSnaps, "Delay before the first frame", at,
                        )
                        Control.Stack -> SliderPanel(
                            "Stack", StackPlan.label(settings.stackBatch), stackFrac(settings.stackBatch),
                            { onSettings(settings.copy(stackBatch = stackAt(it))) }, stackSnaps,
                            when {
                                settings.stackBatch < 0 -> "Frames are saved without stacking"
                                settings.liveStacking -> "Live stacking (experimental): frames are stacked as they arrive"
                                settings.stackBatch == 0 -> "Stacks once, after the last frame"
                                else -> "Stacks every ${settings.stackBatch} frames, then combines the batches"
                            },
                            at,
                            extra = {
                                TextButton(onClick = { onSettings(settings.copy(deleteOriginals = !settings.deleteOriginals)) }) {
                                    Text(if (settings.deleteOriginals) "Delete originals" else "Keep originals")
                                }
                            },
                        )
                        Control.TrailFrames -> SliderPanel(
                            "Frames per shot", "${settings.trailFrames}", idxFrac(TRAIL_FRAME_STOPS, settings.trailFrames),
                            { onSettings(settings.copy(trailFrames = idxAt(TRAIL_FRAME_STOPS, it))) },
                            idxSnaps(TRAIL_FRAME_STOPS) { it.toString() },
                            "Averaged into one shot to cut noise. More frames make each shot longer.", at,
                        )
                        Control.Every -> SliderPanel(
                            "Every", intervalLabel(settings.trailInterval), idxFrac(TRAIL_EVERY_STOPS, settings.trailInterval),
                            { onSettings(settings.copy(trailInterval = idxAt(TRAIL_EVERY_STOPS, it))) },
                            idxSnaps(TRAIL_EVERY_STOPS, ::intervalShort),
                            if (gapPx <= 2.0) "Trails will be continuous" else "Dotted trails, gaps ≈ ${gapPx.roundToInt()} px",
                            at,
                        )
                        Control.Duration -> SliderPanel(
                            "For", minutesLabel(settings.trailMinutes), idxFrac(TRAIL_MINUTE_STOPS, settings.trailMinutes),
                            { onSettings(settings.copy(trailMinutes = idxAt(TRAIL_MINUTE_STOPS, it))) },
                            idxSnaps(TRAIL_MINUTE_STOPS, ::minutesShort),
                            "Trails ≈ ${(trailSeconds * pxPerSec).roundToInt()} px long. Keep the phone charging and still.", at,
                        )
                    }
                }
                if (countdown > 0) {
                    Text(countdown.toString(), style = MaterialTheme.typography.displayLarge,
                        color = colors.onBackground, modifier = Modifier.align(Alignment.Center))
                }
            }
        } else {
            Text("No usable preview size for this camera.", color = colors.error, modifier = Modifier.padding(20.dp))
        }

        // One strip of settings. Each chip opens its slider over the preview.
        // Star trails mode swaps the last three for frames per shot, interval and duration.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            fun open(c: Control) {
                if (active == c && panelOpen) {
                    panelOpen = false
                } else {
                    active = c
                    panelOpen = true
                }
            }
            StripChip("EXP", if (ms.manual) exposureText else "Auto", showPanel && active == Control.Exposure,
                ms.manual && !busy, Modifier.weight(1f)) { open(Control.Exposure) }
            StripChip("ISO", if (ms.manual) ms.iso.toString() else "Auto", showPanel && active == Control.Iso,
                ms.manual && !busy, Modifier.weight(1f)) { open(Control.Iso) }
            StripChip("FOCUS", if (ms.manual && limits.manualFocus) focusText else "Auto", showPanel && active == Control.Focus,
                ms.manual && limits.manualFocus && !busy, Modifier.weight(1f)) { open(Control.Focus) }
            if (trails) {
                StripChip("SHOT", "${settings.trailFrames}×", showPanel && active == Control.TrailFrames,
                    !busy, Modifier.weight(1f)) { open(Control.TrailFrames) }
                StripChip("EVERY", intervalShort(settings.trailInterval), showPanel && active == Control.Every,
                    !busy, Modifier.weight(1f)) { open(Control.Every) }
                StripChip("FOR", minutesShort(settings.trailMinutes), showPanel && active == Control.Duration,
                    !busy, Modifier.weight(1f)) { open(Control.Duration) }
            } else {
                StripChip("FRAMES", settings.frameCount.toString(), showPanel && active == Control.Frames,
                    !busy, Modifier.weight(1f)) { open(Control.Frames) }
                StripChip("TIMER", if (settings.timerSeconds == 0) "Off" else "${settings.timerSeconds}s",
                    showPanel && active == Control.Timer, !busy, Modifier.weight(1f)) { open(Control.Timer) }
                StripChip("STACK", StackPlan.short(settings.stackBatch), showPanel && active == Control.Stack,
                    !busy, Modifier.weight(1f)) { open(Control.Stack) }
            }
        }

        // Bottom row: last capture, shutter, Manual or Auto.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(lastThumb) { lastLocation?.let { RecentCapture.open(context, it) } }
            Shutter(
                enabled = (canCapture && !busy) || busy,
                label = when {
                    countdown > 0 -> countdown.toString()
                    stackDone -> "✓"
                    busy && trailRun && countdown == 0 -> "■"
                    burst -> frameIndex.toString()
                    busy -> "…"
                    else -> ""
                },
                // While frames are captured the ring shows the burst; while stacking, the stack.
                progress = stackFraction ?: if (burst && frameTotal > 0) frameIndex.toFloat() / frameTotal else null,
                onClick = {
                    when {
                        countdown > 0 -> { job?.cancel(); status = "Timer cancelled" }
                        // A star trails session finishes and saves what it has instead of being thrown away.
                        busy && trailRun -> { stopRequested = true; phase = "Finishing…" }
                        busy -> { job?.cancel(); status = "Stopped. Frames already saved were kept." }
                        else -> {
                            val needsAsk = if (trails) settings.originals == OriginalsPolicy.Ask
                            else settings.frameCount > 1 && settings.stackBatch >= 0 && settings.originals == OriginalsPolicy.Ask
                            if (needsAsk) askDialog = true else begin(settings.originals == OriginalsPolicy.Delete)
                        }
                    }
                },
            )
            StripChip(
                "MODE", if (ms.manual) "Manual" else "Auto", ms.manual, limits.manualAvailable && !busy,
                Modifier.width(72.dp),
            ) { manual = !manual }
        }
    }

    if (askDialog) {
        AlertDialog(
            onDismissRequest = { askDialog = false },
            title = { Text("Keep the original frames?") },
            text = {
                Text(
                    "After stacking, the original frames can be deleted to save storage. Nothing is deleted " +
                        "until the stacked image is saved and checked."
                )
            },
            confirmButton = { TextButton(onClick = { askDialog = false; begin(true) }) { Text("Delete originals") } },
            dismissButton = { TextButton(onClick = { askDialog = false; begin(false) }) { Text("Keep originals") } },
        )
    }
    resumePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Phone has cooled down") },
            text = { Text("The burst paused because the phone got hot. Resume capturing the remaining frames?") },
            confirmButton = { TextButton(onClick = { prompt.complete(true) }) { Text("Resume") } },
            dismissButton = { TextButton(onClick = { prompt.complete(false) }) { Text("Stop here") } },
        )
    }
    if (tempDialog) {
        AlertDialog(
            onDismissRequest = { tempDialog = false },
            title = { Text("Phone temperature") },
            text = {
                Text(
                    Thermal.summary(thermal) + "\n\nLevel: ${level.name.lowercase()}. " +
                        if (settings.tempProtection) "Protection is on (${settings.tempPreset.label})." else "Protection is off."
                )
            },
            confirmButton = { TextButton(onClick = { tempDialog = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun SliderPanel(
    title: String,
    valueText: String,
    frac: Float,
    onFrac: (Float) -> Unit,
    snaps: List<Snap>,
    hint: String,
    modifier: Modifier,
    extra: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            extra()
            Text(valueText, color = colors.onBackground, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp))
        }
        SnapSlider(value = frac, onValueChange = onFrac, snaps = snaps)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun StripChip(
    label: String,
    value: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (selected) colors.primary else colors.outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) colors.onBackground else colors.outline,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun Thumbnail(bitmap: Bitmap?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier.size(56.dp).clip(shape).border(1.dp, colors.outline, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Last capture",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, label: String, progress: Float?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxSize(),
                color = colors.primary,
                strokeWidth = 4.dp,
            )
        }
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(4.dp, if (enabled) colors.onBackground else colors.outline, CircleShape)
                .padding(7.dp)
                .clip(CircleShape)
                .background(if (enabled) colors.onBackground else colors.outline)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = colors.background, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
