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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.astrocam.app.camera.CrashLog
import com.astrocam.app.camera.LiveTest
import com.astrocam.app.camera.LiveTestResult
import com.astrocam.app.camera.ManualSettings
import com.astrocam.app.camera.PreviewController
import com.astrocam.app.camera.ProfileStore
import com.astrocam.app.camera.deviceName
import com.astrocam.app.camera.exposureAt
import com.astrocam.app.camera.isoAt
import com.astrocam.app.camera.isoFraction
import com.astrocam.app.camera.previewConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTestScreen(limits: ControlLimits?, onBack: () -> Unit, onSaved: (LiveTestResult) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Live Stacking Test") },
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
            LiveTestContent(limits, onSaved, Modifier.padding(padding))
        }
    }
}

@Composable
private fun LiveTestContent(limits: ControlLimits, onSaved: (LiveTestResult) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val cameraId = limits.camera.id

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permission.launch(Manifest.permission.CAMERA) }

    val config = remember(cameraId) { previewConfig(context, cameraId) }
    val controller = remember { PreviewController(context) { } }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var resumed by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var frames by remember { mutableStateOf(10) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<LiveTestResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    // The test uses the longest verified exposure at ISO 800, like a real star shot.
    val ms = ManualSettings(
        manual = limits.manualAvailable,
        exposureNs = limits.exposureAt(1f),
        iso = limits.isoAt(limits.isoFraction(800)),
        focusDiopters = 0f,
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { resumed = false; ready = false; controller.stop() }
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
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    val surfaceTexture = texture
    LaunchedEffect(surfaceTexture, hasPermission, resumed) {
        if (surfaceTexture != null && hasPermission && resumed && config != null) {
            previewError = controller.start(cameraId, surfaceTexture, config.size, config.jpegSize, ms)
            ready = previewError == null && controller.canCapture
        }
    }

    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Takes a short burst twice: once only capturing, and once while every frame is aligned and " +
                "stacked as it arrives. Point the phone at stars or a scene with bright points, and keep it still.",
            color = colors.onSurfaceVariant,
        )
        if (config != null) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { texture = st }
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
                modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(config.aspect),
            )
        }
        previewError?.let { Text("Preview failed: $it", color = colors.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20).forEach { n ->
                if (frames == n) Button(onClick = {}, enabled = !running) { Text("$n frames") }
                else OutlinedButton(onClick = { frames = n }, enabled = !running) { Text("$n frames") }
            }
        }

        if (running) {
            OutlinedButton(onClick = { job?.cancel(); progress = "Cancelled" }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        } else {
            Button(
                onClick = {
                    val cfg = config ?: return@Button
                    job = scope.launch {
                        running = true
                        result = null
                        errorText = null
                        try {
                            val r = LiveTest.run(context, controller, ms, cfg.sensorOrientation, frames) { progress = it }
                            result = r
                            ProfileStore.saveLive(context, r)
                            onSaved(r)
                            progress = ""
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            CrashLog.record(context, e)
                            errorText = "The test failed: ${e.message ?: e.javaClass.simpleName}. Share the error from Settings, Diagnostics."
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ready) "Run test" else "Waiting for the camera…") }
        }
        if (progress.isNotEmpty()) Text(progress, color = colors.onSurfaceVariant)
        errorText?.let { Text(it, color = colors.error) }

        result?.let { r ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.verdict, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(r.summary)
                    OutlinedButton(
                        onClick = {
                            shareReport(
                                context,
                                "Astro Camera - Live Stacking Test\nDevice: ${deviceName()}\n\n${r.verdict}\n${r.summary}",
                                "Astro Camera live stacking test",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Share result") }
                }
            }
        }
    }
}
