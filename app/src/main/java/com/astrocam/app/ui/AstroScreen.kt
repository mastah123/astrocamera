package com.astrocam.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

    var manual by remember { mutableStateOf(limits.manualAvailable) }
    var exposureFrac by remember { mutableStateOf(1f) }
    var isoFrac by remember { mutableStateOf(limits.isoFraction(800)) }
    var focusFrac by remember { mutableStateOf(0f) }

    val settings = ManualSettings(
        manual = manual && limits.manualAvailable,
        exposureNs = limits.exposureAt(exposureFrac),
        iso = limits.isoAt(isoFrac),
        focusDiopters = focusFrac * limits.minFocusDiopters,
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { resumed = false; controller.stop() }
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
            error = controller.start(cameraId, surfaceTexture, config.size, settings)
        }
    }
    LaunchedEffect(settings) { controller.update(settings) }

    // The preview stays fixed at the top; only the controls below it scroll.
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (config != null) {
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
                modifier = Modifier.fillMaxWidth().aspectRatio(config.aspect),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                !hasPermission -> {
                    Text("Camera permission is needed for the preview.")
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Grant access") }
                }
                error != null -> Text("Preview failed: $error", color = colors.error)
                config == null -> Text("No usable preview size for this camera.", color = colors.error)
            }

            reading?.let {
                Text(
                    "Camera reports: ${it.exposureNs?.let(::formatExposure) ?: "n/a"} · " +
                        "ISO ${it.iso ?: "n/a"} · frame ${it.frameNs?.let(::formatExposure) ?: "n/a"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Manual exposure", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(checked = manual && limits.manualAvailable, onCheckedChange = { manual = it },
                    enabled = limits.manualAvailable)
            }

            if (settings.manual) {
                ControlLabel("Exposure", formatExposure(settings.exposureNs))
                Slider(value = exposureFrac, onValueChange = { exposureFrac = it })
                Text(
                    if (limits.exposureVerified) "Verified limit: up to ${formatExposure(limits.maxExposureNs)}"
                    else "Unverified cap of ${formatExposure(limits.maxExposureNs)}. Run the Long Exposure " +
                        "Test (Settings, Experimental) to unlock the real limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )

                ControlLabel("ISO", settings.iso.toString())
                Slider(value = isoFrac, onValueChange = { isoFrac = it })
                Text("Range as reported by the phone.", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)

                if (limits.manualFocus) {
                    val focusText = if (focusFrac < 0.01f) "∞"
                    else "%.0f cm".format(Locale.US, 100f / (focusFrac * limits.minFocusDiopters))
                    ControlLabel("Focus", focusText)
                    Slider(value = focusFrac, onValueChange = { focusFrac = it })
                    OutlinedButton(onClick = { focusFrac = 0f }) { Text("Infinity") }
                    Text("Manual focus is reported by the phone but not yet verified.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else {
                    Text("Manual focus is not reported by this camera.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            } else {
                Text(
                    if (limits.manualAvailable) "Auto exposure and focus are on."
                    else "This camera does not report manual exposure or ISO ranges.",
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlLabel(name: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
