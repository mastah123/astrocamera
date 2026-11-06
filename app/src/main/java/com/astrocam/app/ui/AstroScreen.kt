package com.astrocam.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.TextureView
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
import androidx.compose.material3.Slider
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
import com.astrocam.app.camera.CaptureStore
import com.astrocam.app.camera.ControlLimits
import com.astrocam.app.camera.CrashLog
import com.astrocam.app.camera.FrameRef
import com.astrocam.app.camera.LiveReading
import com.astrocam.app.camera.ManualSettings
import com.astrocam.app.camera.Originals
import com.astrocam.app.camera.OriginalsPolicy
import com.astrocam.app.camera.PreviewController
import com.astrocam.app.camera.SessionInfo
import com.astrocam.app.camera.StackResult
import com.astrocam.app.camera.StackWhile
import com.astrocam.app.camera.Stacker
import com.astrocam.app.camera.Thermal
import com.astrocam.app.camera.ThermalLevel
import com.astrocam.app.camera.exposureAt
import com.astrocam.app.camera.formatExposure
import com.astrocam.app.camera.isoAt
import com.astrocam.app.camera.isoFraction
import com.astrocam.app.camera.previewConfig
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Control { Exposure, Iso, Focus }

private val TIMER_CHOICES = listOf(0, 2, 5, 10)
private val FRAME_CHOICES = listOf(1, 5, 10, 20, 50, 100)

private fun formatTotal(ns: Long): String {
    val seconds = ns / 1e9
    return if (seconds < 60) "%.1f s".format(Locale.US, seconds) else "%.1f min".format(Locale.US, seconds / 60)
}

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

@Composable
fun AstroScreen(
    limits: ControlLimits?,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    onOpenStack: () -> Unit,
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
            AstroContent(limits, settings, onSettings, onOpenStack, onBack, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AstroContent(
    limits: ControlLimits,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    onOpenStack: () -> Unit,
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

    var manual by remember { mutableStateOf(limits.manualAvailable) }
    var active by remember { mutableStateOf(Control.Exposure) }
    var panelOpen by remember { mutableStateOf(true) }
    var exposureFrac by remember { mutableStateOf(1f) }
    var isoFrac by remember { mutableStateOf(limits.isoFraction(800)) }
    var focusFrac by remember { mutableStateOf(0f) }

    var countdown by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var frameIndex by remember { mutableStateOf(0) }
    var frameTotal by remember { mutableStateOf(1) }
    var savedCount by remember { mutableStateOf(0) }
    var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
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
    )
    val latestManual by rememberUpdatedState(ms)
    val latestSettings by rememberUpdatedState(settings)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { resumed = false; canCapture = false; controller.stop() }
                Lifecycle.Event.ON_RESUME -> resumed = true
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
            frameIndex = 0
            val app = latestSettings
            frameTotal = app.frameCount
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
                val mode = if (total > 1) app.stackWhile else StackWhile.Off
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

                suspend fun reap() {
                    val ref = pending.removeFirst().await()
                    if (ref != null) {
                        savedCount++
                        savedFrames += ref
                        batchFrames += ref
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
                    phase = "Stacking batch $batchNo…"
                    val info = SessionInfo(
                        "${session}_B%02d".format(Locale.US, batchNo), refs, used.exposureNs, used.iso, used.manual,
                    )
                    val r = try {
                        Stacker.stack(context, info, app.autoMethod, false, folder = "AstroCamera/STACKED/BATCHES", previews = false) { _, _ -> }
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
                    if (pending.size >= 3) reap()
                    if (mode.batch > 0 && i % mode.batch == 0) {
                        drain()
                        stackBatch()
                    }
                }
                drain()

                var finalResult: StackResult? = null
                if (mode != StackWhile.Off && savedFrames.isNotEmpty() && session != null) {
                    try {
                        if (mode == StackWhile.AfterBurst) {
                            phase = "Stacking ${savedFrames.size} frames…"
                            val info = SessionInfo(session, savedFrames.toList(), used.exposureNs, used.iso, used.manual)
                            finalResult = Stacker.stack(context, info, app.autoMethod, app.boost) { label, _ -> phase = label }
                        } else {
                            stackBatch()
                            if (batchImages.isNotEmpty()) {
                                phase = "Combining ${batchImages.size} batches…"
                                val info = SessionInfo(session, batchImages.toList(), used.exposureNs, used.iso, used.manual)
                                finalResult = Stacker.stack(
                                    context, info, app.autoMethod, app.boost, weights = batchWeights.toList(),
                                ) { label, _ -> phase = label }
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
                result?.stacked?.let { lastThumb = it }

                status = buildString {
                    stopReason?.let { append(it).append(' ') }
                    when {
                        total == 1 -> append(
                            savedFrames.firstOrNull()?.let { "Saved ${it.name}" } ?: "Capture failed. Try again."
                        )
                        result != null && result.verified -> {
                            append("Stacked ${result.used} frames into ${result.savedName}.")
                            if (deleted > 0) append(" Deleted $deleted originals.")
                        }
                        mode != StackWhile.Off -> append("Stacking did not finish, so the originals were kept.")
                        else -> append("Saved $savedCount of $total frames to Pictures/AstroCamera/$session")
                    }
                    if (batchFailed > 0) append(" $batchFailed batch(es) could not be stacked; their frames were kept.")
                }
            } finally {
                busy = false
                countdown = 0
                phase = ""
                resumePrompt = null
            }
        }
    }

    val focusText = if (focusFrac < 0.01f) "∞"
    else "%.0f cm".format(Locale.US, 100f / (focusFrac * limits.minFocusDiopters))
    val exposureText = formatExposure(ms.exposureNs)
    val readout = if (ms.manual) "$exposureText · ISO ${ms.iso}" + (if (limits.manualFocus) " · $focusText" else "") else "Auto"
    val burst = busy && frameTotal > 1 && countdown == 0

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Top bar: back, current settings, heat.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, enabled = !busy) { Text("‹ Back") }
            Text(readout, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium)
            TempChip(thermal, level) { tempDialog = true }
            Spacer(Modifier.width(8.dp))
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
                )
                if (ms.manual && panelOpen) {
                    SliderPanel(
                        limits = limits,
                        ms = ms,
                        active = active,
                        exposureFrac = exposureFrac,
                        onExposure = { exposureFrac = it },
                        isoFrac = isoFrac,
                        onIso = { isoFrac = it },
                        focusFrac = focusFrac,
                        onFocus = { focusFrac = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                if (countdown > 0) {
                    Text(countdown.toString(), style = MaterialTheme.typography.displayLarge,
                        color = colors.onBackground, modifier = Modifier.align(Alignment.Center))
                }
            }
        } else {
            Text("No usable preview size for this camera.", color = colors.error, modifier = Modifier.padding(20.dp))
        }

        // One strip of settings. The first three open a slider; the rest cycle on tap.
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
            StripChip("EXP", if (ms.manual) exposureText else "Auto", ms.manual && panelOpen && active == Control.Exposure,
                ms.manual && !busy, Modifier.weight(1f)) { open(Control.Exposure) }
            StripChip("ISO", if (ms.manual) ms.iso.toString() else "Auto", ms.manual && panelOpen && active == Control.Iso,
                ms.manual && !busy, Modifier.weight(1f)) { open(Control.Iso) }
            StripChip("FOCUS", if (ms.manual && limits.manualFocus) focusText else "Auto",
                ms.manual && panelOpen && active == Control.Focus,
                ms.manual && limits.manualFocus && !busy, Modifier.weight(1f)) { open(Control.Focus) }
            StripChip("FRAMES", settings.frameCount.toString(), settings.frameCount > 1, !busy, Modifier.weight(1f)) {
                onSettings(settings.copy(frameCount = FRAME_CHOICES[(FRAME_CHOICES.indexOf(settings.frameCount) + 1) % FRAME_CHOICES.size]))
            }
            StripChip("TIMER", if (settings.timerSeconds == 0) "Off" else "${settings.timerSeconds}s", settings.timerSeconds > 0,
                !busy, Modifier.weight(1f)) {
                onSettings(settings.copy(timerSeconds = TIMER_CHOICES[(TIMER_CHOICES.indexOf(settings.timerSeconds) + 1) % TIMER_CHOICES.size]))
            }
            StripChip("STACK", settings.stackWhile.short, settings.stackWhile != StackWhile.Off, !busy, Modifier.weight(1f)) {
                val all = StackWhile.values()
                onSettings(settings.copy(stackWhile = all[(settings.stackWhile.ordinal + 1) % all.size]))
            }
        }

        // Status line and progress.
        val info = buildString {
            reading?.let { append("Camera: ${it.exposureNs?.let(::formatExposure) ?: "n/a"} · ISO ${it.iso ?: "n/a"}") }
            if (settings.frameCount > 1 && ms.manual) {
                if (isNotEmpty()) append(" · ")
                append("${settings.frameCount} × $exposureText = ${formatTotal(ms.exposureNs * settings.frameCount)}")
            }
        }
        Text(
            when {
                !hasPermission -> "Camera permission is needed for the preview."
                error != null -> "Preview failed: $error"
                phase.isNotEmpty() -> phase
                burst -> "Frame $frameIndex of $frameTotal · saved $savedCount"
                else -> status ?: info
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (!hasPermission) {
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Grant access") }
        }

        // Bottom row: last capture, shutter, Manual or Auto.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(lastThumb, onOpenStack)
            Shutter(
                enabled = (canCapture && !busy) || busy,
                label = when {
                    countdown > 0 -> countdown.toString()
                    burst -> frameIndex.toString()
                    busy -> "…"
                    else -> ""
                },
                progress = if (burst && frameTotal > 0) frameIndex.toFloat() / frameTotal else null,
                onClick = {
                    when {
                        countdown > 0 -> { job?.cancel(); status = "Timer cancelled" }
                        busy -> { job?.cancel(); status = "Stopped. Frames already saved were kept." }
                        else -> {
                            val needsAsk = settings.frameCount > 1 && settings.stackWhile != StackWhile.Off &&
                                settings.originals == OriginalsPolicy.Ask
                            if (needsAsk) askDialog = true else shoot(settings.originals == OriginalsPolicy.Delete)
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
            confirmButton = { TextButton(onClick = { askDialog = false; shoot(true) }) { Text("Delete originals") } },
            dismissButton = { TextButton(onClick = { askDialog = false; shoot(false) }) { Text("Keep originals") } },
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
    limits: ControlLimits,
    ms: ManualSettings,
    active: Control,
    exposureFrac: Float,
    onExposure: (Float) -> Unit,
    isoFrac: Float,
    onIso: (Float) -> Unit,
    focusFrac: Float,
    onFocus: (Float) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shown = if (active == Control.Focus && !limits.manualFocus) Control.Exposure else active
    val name: String
    val value: String
    val frac: Float
    val onChange: (Float) -> Unit
    val hint: String
    when (shown) {
        Control.Exposure -> {
            name = "Exposure"; value = formatExposure(ms.exposureNs); frac = exposureFrac; onChange = onExposure
            hint = if (limits.exposureVerified) "Verified max ${formatExposure(limits.maxExposureNs)}"
            else "Unverified max ${formatExposure(limits.maxExposureNs)}. Run the Long Exposure Test."
        }
        Control.Iso -> {
            name = "ISO"; value = ms.iso.toString(); frac = isoFrac; onChange = onIso
            hint = "Range as reported by the phone"
        }
        Control.Focus -> {
            name = "Focus"
            value = if (focusFrac < 0.01f) "∞" else "%.0f cm".format(Locale.US, 100f / (focusFrac * limits.minFocusDiopters))
            frac = focusFrac; onChange = onFocus
            hint = "Reported by the phone, not yet verified. Drag fully left for infinity."
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, color = colors.onBackground, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = frac, onValueChange = onChange)
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
