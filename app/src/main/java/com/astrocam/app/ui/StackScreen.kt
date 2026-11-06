package com.astrocam.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astrocam.app.camera.CaptureLog
import com.astrocam.app.camera.SessionInfo
import com.astrocam.app.camera.StackMode
import com.astrocam.app.camera.StackResult
import com.astrocam.app.camera.Stacker
import com.astrocam.app.camera.Stretch
import com.astrocam.app.camera.formatExposure
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    val sessions = remember { CaptureLog.sessions(context) }
    var selected by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(StackMode.Average) }
    var stretch by remember { mutableStateOf(Stretch.Medium) }
    var running by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<StackResult?>(null) }
    var showStacked by remember { mutableStateOf(true) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
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
            if (sessions.isEmpty()) {
                Text(
                    "No multi-frame captures yet. In Astro Camera, set 5 or more frames and take a sequence, " +
                        "then come back here.",
                    color = colors.onSurfaceVariant,
                )
                return@Column
            }

            Text("Capture", fontWeight = FontWeight.SemiBold)
            sessions.forEachIndexed { index, s ->
                OptionRow(
                    selected = selected == index,
                    enabled = !running,
                    title = s.session.removePrefix("SESSION_"),
                    detail = describe(s),
                ) { selected = index }
            }

            Text("Method", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            StackMode.values().forEach { m ->
                OptionRow(mode == m, !running, m.label, m.hint) { mode = m }
            }

            Text("Brightness boost", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Stretch.values().forEach { st ->
                OptionRow(stretch == st, !running, st.label,
                    if (st == Stretch.Off) "Keep the stacked result as it is" else "Brighten faint detail") { stretch = st }
            }

            if (running) {
                OutlinedButton(onClick = { job?.cancel(); progressLabel = "Cancelled" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Text(progressLabel, color = colors.onSurfaceVariant)
            } else {
                Button(
                    onClick = {
                        val chosen = sessions[selected]
                        job = scope.launch {
                            running = true
                            result = null
                            progress = 0f
                            try {
                                result = Stacker.stack(context, chosen, mode, stretch) { label, fraction ->
                                    progressLabel = label
                                    progress = fraction
                                }
                                showStacked = true
                                progressLabel = ""
                            } finally {
                                running = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stack frames") }
                if (progressLabel.isNotEmpty()) Text(progressLabel, color = colors.onSurfaceVariant)
            }

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
}

private fun describe(s: SessionInfo): String {
    val settings = if (s.manual) "${formatExposure(s.exposureNs)} · ISO ${s.iso}" else "auto"
    return "${s.frames.size} frames · $settings"
}

@Composable
private fun OptionRow(selected: Boolean, enabled: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
