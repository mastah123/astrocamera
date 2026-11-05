package com.astrocam.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.astrocam.app.camera.CameraInfo
import com.astrocam.app.camera.ProfileStore
import com.astrocam.app.camera.RawProbe
import com.astrocam.app.camera.RawResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawScreen(
    cameras: List<CameraInfo>,
    onBack: () -> Unit,
    onSaved: (Map<String, Boolean>) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var results by remember { mutableStateOf(listOf<Pair<String, RawResult>>()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    val start: () -> Unit = {
        if (!running) {
            job = scope.launch {
                running = true
                results = emptyList()
                val collected = mutableListOf<Pair<String, RawResult>>()
                try {
                    for (camera in cameras.filter { it.facing == "Back" }) {
                        val result = RawProbe.run(context, camera.id) { progress = it }
                        collected += camera.id to result
                        results = collected.toList()
                        onSaved(ProfileStore.saveRaw(context, camera.id, result.ok))
                    }
                    progress = if (collected.isEmpty()) "No rear camera to test." else "Done"
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
                title = { Text("RAW Capture Test") },
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
            Text(
                "Takes one real RAW photo from each rear camera, checks that the pixels are not blank, " +
                    "and saves a DNG file. Phones sometimes list RAW support they do not have.",
                color = colors.onSurfaceVariant,
            )
            if (running) {
                OutlinedButton(onClick = { job?.cancel(); progress = "Cancelled" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
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
            results.forEach { (id, result) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Camera $id", color = colors.onSurfaceVariant)
                        Text(if (result.ok) "RAW capture passed" else "RAW capture failed",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(result.message)
                    }
                }
            }
        }
    }
}
