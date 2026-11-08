package com.astrocam.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.astrocam.app.camera.AppSettings
import com.astrocam.app.camera.CaptureLog
import com.astrocam.app.camera.CrashLog
import com.astrocam.app.camera.DarkFrames
import com.astrocam.app.camera.Originals
import com.astrocam.app.camera.OriginalsPolicy
import com.astrocam.app.camera.ProgressTracker
import com.astrocam.app.camera.formatProgress
import com.astrocam.app.camera.SessionInfo
import com.astrocam.app.camera.StackMode
import com.astrocam.app.camera.StackResult
import com.astrocam.app.camera.Stacker
import com.astrocam.app.camera.formatExposure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackScreen(settings: AppSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    var sessions by remember { mutableStateOf(CaptureLog.sessions(context)) }
    var selected by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(StackMode.Average) }
    var boost by remember { mutableStateOf(settings.boost) }
    var binOn by remember { mutableStateOf(settings.nightBin) }
    var running by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<StackResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showStacked by remember { mutableStateOf(true) }
    var job by remember { mutableStateOf<Job?>(null) }
    val tracker = remember { ProgressTracker() }
    var deleteQuestion by remember { mutableStateOf(false) }

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    // Removes the originals that went into a verified result, then refreshes the list.
    fun deleteOriginals(r: StackResult) {
        val removed = Originals.delete(context, r.usedRefs)
        sessions = CaptureLog.sessions(context)
        selected = 0
        result = StackResult(
            r.original, r.stacked, r.used, r.rejected, r.savedName, r.savedLocation, r.verified, r.usedRefs,
            r.message + " Deleted $removed original frames.",
        )
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Stacking Studio") },
                navigationIcon = { TextButton(onClick = onBack, enabled = !running) { Text("Back") } },
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
            if (sessions.isEmpty() && result == null) {
                Text(
                    "No multi-frame captures yet. In Astro Camera, set 5 or more frames and take a sequence, " +
                        "then come back here.",
                    color = colors.onSurfaceVariant,
                )
                return@Column
            }

            if (sessions.isNotEmpty()) {
                SectionTitle("Capture")
                sessions.forEachIndexed { index, s ->
                    OptionRow(selected == index, s.session.removePrefix("SESSION_"), describe(s), !running) { selected = index }
                }

                SectionTitle("Method")
                StackMode.values().forEach { m -> OptionRow(mode == m, m.label, m.hint, !running) { mode = m } }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Brightness boost")
                        Text("Brightens faint detail", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(checked = boost, onCheckedChange = { boost = it }, enabled = !running)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Night noise reduction (2×2)")
                        Text("Halves the picture size and cuts noise about 2×", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    Switch(checked = binOn, onCheckedChange = { binOn = it }, enabled = !running)
                }
                val chosenSession = sessions.getOrNull(selected)
                val darkUsed = chosenSession != null && chosenSession.manual &&
                    DarkFrames.exists(context, chosenSession.exposureNs, chosenSession.iso)
                Text(
                    if (darkUsed) "Dark frame: found for this capture and will be subtracted"
                    else "Dark frame: none. Cover the lens and tap ◐ Dark on the camera screen to make one",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    "Original frames after stacking: ${settings.originals.label.lowercase()} (change in Settings)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            if (running) {
                OutlinedButton(onClick = { job?.cancel(); progressLabel = "Cancelled" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Text(progressLabel, color = colors.onSurfaceVariant)
            } else if (sessions.isNotEmpty()) {
                Button(
                    onClick = {
                        val chosen: SessionInfo = sessions[selected]
                        job = scope.launch {
                            running = true
                            result = null
                            errorText = null
                            progress = 0f
                            try {
                                tracker.begin()
                                val dark = if (chosen.manual) {
                                    withContext(Dispatchers.IO) { DarkFrames.load(context, chosen.exposureNs, chosen.iso) }
                                } else null
                                val r = Stacker.stack(context, chosen, mode, boost, bin = binOn, dark = dark) { label, fraction ->
                                    progressLabel = formatProgress(label, fraction, tracker.etaSeconds(fraction))
                                    progress = fraction
                                }
                                result = r
                                showStacked = true
                                progressLabel = if (r.verified) "Done ✓" else "Finished, but the result could not be verified"
                                buzz(context, 150)
                                if (r.verified) {
                                    when (settings.originals) {
                                        OriginalsPolicy.Delete -> deleteOriginals(r)
                                        OriginalsPolicy.Ask -> deleteQuestion = true
                                        OriginalsPolicy.Keep -> Unit
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // Show the problem instead of closing the app, and keep it for Settings, Diagnostics.
                                CrashLog.record(context, e)
                                errorText = "Stacking failed: ${e.message ?: e.javaClass.simpleName}. " +
                                    "Your frames were not changed. Share the error from Settings, Diagnostics."
                            } finally {
                                running = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stack frames") }
                if (progressLabel.isNotEmpty()) Text(progressLabel, color = colors.onSurfaceVariant)
            }

            errorText?.let { Text(it, color = colors.error) }

            result?.let { r ->
                Text(r.message)
                val stacked = r.stacked
                val original = r.original
                if (stacked != null) {
                    if (original != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showStacked) Button(onClick = {}) { Text("Stacked") }
                            else OutlinedButton(onClick = { showStacked = true }) { Text("Stacked") }
                            if (!showStacked) Button(onClick = {}) { Text("Single frame") }
                            else OutlinedButton(onClick = { showStacked = false }) { Text("Single frame") }
                        }
                    }
                    Image(
                        bitmap = (if (showStacked || original == null) stacked else original).asImageBitmap(),
                        contentDescription = if (showStacked) "Stacked image" else "Single frame",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
    }

    val pending = result
    if (deleteQuestion && pending != null) {
        AlertDialog(
            onDismissRequest = { deleteQuestion = false },
            title = { Text("Delete the original frames?") },
            text = {
                Text(
                    "The stacked image was saved and checked. Delete the ${pending.usedRefs.size} original frames " +
                        "that went into it to save storage? Frames that could not be used are kept."
                )
            },
            confirmButton = { TextButton(onClick = { deleteQuestion = false; deleteOriginals(pending) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteQuestion = false }) { Text("Keep") } },
        )
    }
}

private fun describe(s: SessionInfo): String {
    val settings = if (s.manual) "${formatExposure(s.exposureNs)} · ISO ${s.iso}" else "auto"
    return "${s.frames.size} frames · $settings"
}
