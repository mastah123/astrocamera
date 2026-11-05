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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Control { Exposure, Iso, Focus }

private val TIMER_CHOICES = listOf(0, 2, 5, 10)

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
            try {
                var remaining = timerSeconds
                while (remaining > 0) {
                    countdown = remaining
                    delay(1000)
                    remaining--
                }
                countdown = 0
                val used = latestSettings
                val outcome = controller.capture(used, cfg.sensorOrientation)
                status = if (outcome == null) {
                    "Capture failed. Try again."
                } else {
                    val name = withContext(Dispatchers.IO) {
                        CaptureStore.save(context, outcome.jpeg, used, outcome.reading, cameraId)
                    }
                    if (name != null) "Saved $name" else "Could not save the photo."
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
            when {
                !hasPermission -> {
                    Text("Camera permission is needed for the preview.")
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Grant access") }
                }
                error != null -> Text("Preview failed: $error", color = colors.error, textAlign = TextAlign.Center)
                else -> {
                    Text(
                        status ?: reading?.let {
                            "Camera: ${it.exposureNs?.let(::formatExposure) ?: "n/a"} · " +
                                "ISO ${it.iso ?: "n/a"} · frame ${it.frameNs?.let(::formatExposure) ?: "n/a"}"
                        } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
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
                Shutter(
                    enabled = (canCapture && !busy) || countdown > 0,
                    countdown = countdown,
                    busy = busy,
                    onClick = {
                        when {
                            countdown > 0 -> { job?.cancel(); status = "Timer cancelled" }
                            !busy -> shoot()
                        }
                    },
                )
                Chip(
                    label = if (settings.manual) "Manual" else "Auto",
                    selected = settings.manual,
                    enabled = limits.manualAvailable && !busy,
                    onClick = { manual = !manual },
                )
            }
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
private fun Shutter(enabled: Boolean, countdown: Int, busy: Boolean, onClick: () -> Unit) {
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
            when {
                countdown > 0 -> countdown.toString()
                busy -> "…"
                else -> ""
            },
            color = colors.background,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
