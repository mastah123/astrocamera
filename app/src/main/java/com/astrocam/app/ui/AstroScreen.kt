package com.astrocam.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.astrocam.app.camera.CaptureStore
import com.astrocam.app.camera.ControlLimits
import com.astrocam.app.camera.LiveReading
import com.astrocam.app.camera.ManualSettings
import com.astrocam.app.camera.PreviewController
import com.astrocam.app.camera.exposureAt
import com.astrocam.app.camera.formatExposure
import com.astrocam.app.camera.isoAt
import com.astrocam.app.camera.isoFraction
import com.astrocam.app.camera.previewConfig
import java.util.Locale
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Control { Exposure, Iso, Focus }

private val TIMER_CHOICES = listOf(0, 2, 5, 10)
private val FRAME_CHOICES = listOf(1, 5, 10, 20, 50, 100)

private fun formatTotal(ns: Long): String {
    val seconds = ns / 1e9
    return if (seconds < 60) "%.1f s".format(Locale.US, seconds) else "%.1f min".format(Locale.US, seconds / 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstroScreen(limits: ControlLimits?, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Astro Camera") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        if (limits == null) {
            Text("No rear camera was found on this device.", modifier = Modifier.padding(padding).padding(20.dp))
        } else {
            AstroContent(limits, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AstroContent(limits: ControlLimits, modifier: Modifier) {
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
    var exposureFrac by remember { mutableStateOf(1f) }
    var isoFrac by remember { mutableStateOf(limits.isoFraction(800)) }
    var focusFrac by remember { mutableStateOf(0f) }

    var timerSeconds by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var frameCount by remember { mutableStateOf(1) }
    var frameIndex by remember { mutableStateOf(0) }
    var savedCount by remember { mutableStateOf(0) }

    val settings = ManualSettings(
        manual = manual && limits.manualAvailable,
        exposureNs = limits.exposureAt(exposureFrac),
        iso = limits.isoAt(isoFrac),
        focusDiopters = focusFrac * limits.minFocusDiopters,
    )
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
            error = controller.start(cameraId, surfaceTexture, config.size, config.jpegSize, settings)
            canCapture = error == null && controller.canCapture
        }
    }
    LaunchedEffect(settings) { controller.update(settings) }

    fun shoot() {
        val cfg = config ?: return
        job = scope.launch {
            busy = true
            status = null
            savedCount = 0
            frameIndex = 0
            try {
                var remaining = timerSeconds
                while (remaining > 0) {
                    countdown = remaining
                    delay(1000)
                    remaining--
                }
                countdown = 0

                // Every frame uses the same settings, taken when the sequence starts.
                val used = latestSettings
                val total = frameCount
                val session = if (total > 1) CaptureStore.newSession() else null
                val minimal = total > 1 && used.manual
                val pending = ArrayDeque<Deferred<String?>>()
                var lastName: String? = null
                var failures = 0

                suspend fun reap() {
                    val name = pending.removeFirst().await()
                    if (name != null) {
                        savedCount++
                        lastName = name
                    }
                }

                for (i in 1..total) {
                    frameIndex = i
                    val outcome = controller.capture(used, cfg.sensorOrientation, minimal)
                    if (outcome == null) {
                        failures++
                        if (failures >= 3) break
                        continue
                    }
                    // Save in the background while the next frame is being captured.
                    pending.addLast(scope.async(Dispatchers.IO) {
                        CaptureStore.save(context, outcome.jpeg, used, outcome.reading, cameraId, session, i, total)
                    })
                    if (pending.size >= 3) reap()
                }
                while (pending.isNotEmpty()) reap()

                status = when {
                    total == 1 -> lastName?.let { "Saved $it" } ?: "Capture failed. Try again."
                    failures >= 3 -> "Stopped after repeated failures. Saved $savedCount frames."
                    else -> "Saved $savedCount of $total frames to Pictures/AstroCamera/$session"
                }
            } finally {
                busy = false
                countdown = 0
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Preview with the controls laid over its lower edge, so nothing needs scrolling.
        Box(Modifier.fillMaxWidth()) {
            if (config != null) {
                Box(Modifier.fillMaxWidth().aspectRatio(config.aspect)) {
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
                    ControlPanel(
                        limits = limits,
                        settings = settings,
                        active = active,
                        onActive = { active = it },
                        exposureFrac = exposureFrac,
                        onExposure = { exposureFrac = it },
                        isoFrac = isoFrac,
                        onIso = { isoFrac = it },
                        focusFrac = focusFrac,
                        onFocus = { focusFrac = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    if (countdown > 0) {
                        Text(
                            countdown.toString(),
                            style = MaterialTheme.typography.displayLarge,
                            color = colors.onBackground,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            } else {
                Text("No usable preview size for this camera.", color = colors.error,
                    modifier = Modifier.padding(20.dp))
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val info = buildString {
                reading?.let {
                    append("Camera: ${it.exposureNs?.let(::formatExposure) ?: "n/a"} · ISO ${it.iso ?: "n/a"}")
                }
                if (frameCount > 1 && settings.manual) {
                    if (isNotEmpty()) append(" · ")
                    append("$frameCount × ${formatExposure(settings.exposureNs)} = ${formatTotal(settings.exposureNs * frameCount)}")
                }
            }
            when {
                !hasPermission -> {
                    Text("Camera permission is needed for the preview.")
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Grant access") }
                }
                error != null -> Text("Preview failed: $error", color = colors.error, textAlign = TextAlign.Center)
                busy && frameCount > 1 && countdown == 0 -> {
                    Text("Frame $frameIndex of $frameCount · saved $savedCount",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = frameIndex.toFloat() / frameCount,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> Text(
                    status ?: info,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(
                    label = if (timerSeconds == 0) "Timer off" else "${timerSeconds}s timer",
                    selected = timerSeconds > 0,
                    enabled = !busy,
                    onClick = { timerSeconds = TIMER_CHOICES[(TIMER_CHOICES.indexOf(timerSeconds) + 1) % TIMER_CHOICES.size] },
                )
                Chip(
                    label = if (frameCount == 1) "1 frame" else "$frameCount frames",
                    selected = frameCount > 1,
                    enabled = !busy,
                    onClick = { frameCount = FRAME_CHOICES[(FRAME_CHOICES.indexOf(frameCount) + 1) % FRAME_CHOICES.size] },
                )
                Chip(
                    label = if (settings.manual) "Manual" else "Auto",
                    selected = settings.manual,
                    enabled = limits.manualAvailable && !busy,
                    onClick = { manual = !manual },
                )
            }

            Shutter(
                enabled = (canCapture && !busy) || countdown > 0 || (busy && frameCount > 1),
                label = when {
                    countdown > 0 -> countdown.toString()
                    busy && frameCount > 1 -> frameIndex.toString()
                    busy -> "…"
                    else -> ""
                },
                onClick = {
                    when {
                        countdown > 0 -> { job?.cancel(); status = "Timer cancelled" }
                        busy -> { job?.cancel(); status = "Stopped after $savedCount frames" }
                        else -> shoot()
                    }
                },
            )
        }
    }
}

@Composable
private fun ControlPanel(
    limits: ControlLimits,
    settings: ManualSettings,
    active: Control,
    onActive: (Control) -> Unit,
    exposureFrac: Float,
    onExposure: (Float) -> Unit,
    isoFrac: Float,
    onIso: (Float) -> Unit,
    focusFrac: Float,
    onFocus: (Float) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (!settings.manual) {
            Text("Auto exposure and focus", color = colors.onBackground,
                modifier = Modifier.padding(vertical = 8.dp))
            return@Column
        }
        val focusText = if (focusFrac < 0.01f) "∞"
        else "%.0f cm".format(Locale.US, 100f / (focusFrac * limits.minFocusDiopters))
        val fallbackActive = if (active == Control.Focus && !limits.manualFocus) Control.Exposure else active

        when (fallbackActive) {
            Control.Exposure -> SliderRow(
                "Exposure", formatExposure(settings.exposureNs), exposureFrac, onExposure,
                hint = if (limits.exposureVerified) "Verified max ${formatExposure(limits.maxExposureNs)}"
                else "Unverified max ${formatExposure(limits.maxExposureNs)}. Run the Long Exposure Test.",
            )
            Control.Iso -> SliderRow("ISO", settings.iso.toString(), isoFrac, onIso, hint = "Range as reported by the phone")
            Control.Focus -> SliderRow(
                "Focus", focusText, focusFrac, onFocus,
                hint = "Reported by the phone, not yet verified",
                action = { TextButton(onClick = { onFocus(0f) }) { Text("∞") } },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlChip("Exposure", formatExposure(settings.exposureNs), fallbackActive == Control.Exposure,
                Modifier.weight(1f)) { onActive(Control.Exposure) }
            ControlChip("ISO", settings.iso.toString(), fallbackActive == Control.Iso,
                Modifier.weight(1f)) { onActive(Control.Iso) }
            if (limits.manualFocus) {
                ControlChip("Focus", focusText, fallbackActive == Control.Focus,
                    Modifier.weight(1f)) { onActive(Control.Focus) }
            }
        }
    }
}

@Composable
private fun SliderRow(
    name: String,
    value: String,
    frac: Float,
    onChange: (Float) -> Unit,
    hint: String,
    action: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, color = colors.onBackground, fontWeight = FontWeight.SemiBold)
            action()
        }
        Slider(value = frac, onValueChange = onChange)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ControlChip(name: String, value: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (selected) colors.primary else colors.outline, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(value, color = colors.onBackground, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (selected) colors.primary else colors.outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) colors.onBackground else colors.outline)
    }
}

@Composable
private fun Shutter(enabled: Boolean, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
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
        Text(
            label,
            color = colors.background,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
