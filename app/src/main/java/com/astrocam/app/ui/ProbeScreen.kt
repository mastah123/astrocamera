package com.astrocam.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.astrocam.app.camera.BrightnessStatus
import com.astrocam.app.camera.CameraInfo
import com.astrocam.app.camera.ExposureProbe
import com.astrocam.app.camera.ProbeDepth
import com.astrocam.app.camera.ProbeResult
import com.astrocam.app.camera.ProbeStore
import com.astrocam.app.camera.TestedLimit
import com.astrocam.app.camera.assess
import com.astrocam.app.camera.brightnessCheck
import com.astrocam.app.camera.deviceName
import com.astrocam.app.camera.formatExposure
import com.astrocam.app.camera.manualHonored
import com.astrocam.app.camera.maxHonoredNs
import com.astrocam.app.camera.probeReport
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun formatDuration(seconds: Int): String =
    if (seconds < 90) "about $seconds s" else "up to ${(seconds + 59) / 60} min"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeScreen(cameras: List<CameraInfo>, onBack: () -> Unit, onSaved: (TestedLimit) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var results by remember { mutableStateOf(listOf<ProbeResult>()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf(ProbeDepth.Quick) }
    var job by remember { mutableStateOf<Job?>(null) }

    // Keep the screen awake while a long test runs.
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    val start: () -> Unit = {
        if (!running) {
            job = scope.launch {
                running = true
                results = emptyList()
                val collected = mutableListOf<ProbeResult>()
                try {
                    for (camera in cameras.filter { it.facing == "Back" }) {
                        collected += ExposureProbe.run(context, camera, depth) { progress = it }
                        results = collected.toList()
                    }
                    progress = if (collected.isEmpty()) "No rear camera to test." else "Done"
                    collected.filter { it.manualHonored }.maxByOrNull { it.maxHonoredNs }?.let { best ->
                        val confirmed = best.brightnessCheck().status == BrightnessStatus.Confirmed
                        onSaved(ProbeStore.saveIfBetter(context, TestedLimit(best.maxHonoredNs, confirmed)))
                    }
                } finally {
                    running = false
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else progress = "Camera permission is required for this test."
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Long Exposure Test") },
                navigationIcon = { TextButton(onClick = onBack, enabled = !running) { Text("Back") } },
                actions = {
                    if (results.isNotEmpty()) {
                        TextButton(onClick = { shareReport(context, probeReport(deviceName(), results)) }) {
                            Text("Share")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Sends real manual exposure requests, checks what the camera used, and confirms " +
                    "that frames get brighter. Point the phone at a dim scene and keep it still.",
                color = colors.onSurfaceVariant,
            )
            Text("Test depth", fontWeight = FontWeight.SemiBold)
            ProbeDepth.values().forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !running) { depth = option },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = depth == option, onClick = { depth = option }, enabled = !running)
                    Column {
                        Text(option.label)
                        Text(
                            "Worst case ${formatDuration(option.worstCaseSeconds)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "Each step runs only if the previous one worked, so the test stops early on phones " +
                    "with lower limits. Long tests use battery and may warm the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (running) {
                OutlinedButton(
                    onClick = { job?.cancel(); progress = "Cancelled" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            } else {
                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) start() else permission.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Run test") }
            }
            if (progress.isNotEmpty()) Text(progress, color = colors.onSurfaceVariant)
            results.forEach { ResultCard(it) }
        }
    }
}

@Composable
private fun ResultCard(result: ProbeResult) {
    val colors = MaterialTheme.colorScheme
    val verdict = assess(result)
    val brightness = result.brightnessCheck()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Camera ${result.cameraId}", color = colors.onSurfaceVariant)
            Text(verdict.path.title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(verdict.summary, modifier = Modifier.padding(bottom = 8.dp))
            result.steps.forEach { s ->
                val got = s.appliedNs?.let(::formatExposure) ?: "no result"
                val luma = s.luma?.let { "  (luma %.0f)".format(Locale.US, it) } ?: ""
                Text("${formatExposure(s.requestedNs)} → $got  ${if (s.honored) "✓" else "✗"}$luma")
            }
            if (result.manualHonored) {
                Text("Brightness check: ${brightness.status.name.lowercase()}",
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text(brightness.detail, color = colors.onSurfaceVariant)
            }
            if (result.isoRequested != null) {
                Text("ISO ${result.isoRequested} → ${result.isoApplied ?: "n/a"}")
            }
            result.autoBoostNs?.let { Text("Auto exposure, max boost: ${formatExposure(it)}") }
            if (verdict.rows.isNotEmpty()) {
                Text("Software long exposure plan", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp))
                verdict.rows.forEach {
                    Text("${it.targetSeconds} s total → ${it.frames} frames, " +
                        "noise ×${"%.1f".format(Locale.US, it.snrGain)} better")
                }
            }
        }
    }
}
