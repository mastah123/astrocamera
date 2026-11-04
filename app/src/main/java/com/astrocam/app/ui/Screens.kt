package com.astrocam.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astrocam.app.camera.CameraInfo
import com.astrocam.app.camera.assessReadiness
import com.astrocam.app.camera.buildReport
import com.astrocam.app.camera.deviceName
import com.astrocam.app.camera.formatExposure
import com.astrocam.app.camera.rows

@Composable
fun HomeScreen(
    cameras: List<CameraInfo>,
    night: NightTheme,
    onNightChange: (Boolean) -> Unit,
    onOpenTester: () -> Unit,
) {
    val readiness = remember(cameras) { assessReadiness(cameras) }
    val back = remember(cameras) { cameras.filter { it.facing == "Back" } }
    val colors = MaterialTheme.colorScheme

    Scaffold(containerColor = colors.background) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Astro Camera", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "Understand your camera. Unlock its capabilities. Capture the night sky.",
                color = colors.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(readiness.title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(readiness.detail, color = colors.onSurfaceVariant)
                    if (back.isNotEmpty()) {
                        val maxExposure = back.mapNotNull { it.exposureRangeNs?.endInclusive }.maxOrNull()
                        val maxIso = back.mapNotNull { it.isoRange?.endInclusive }.maxOrNull()
                        Text("Longest exposure: ${maxExposure?.let(::formatExposure) ?: "n/a"}")
                        Text("Max ISO: ${maxIso ?: "n/a"}")
                        Text("RAW / DNG: ${if (back.any { it.raw }) "Yes" else "No"}")
                    }
                }
            }

            Button(onClick = onOpenTester, modifier = Modifier.fillMaxWidth()) {
                Text("Camera Tester")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Red night-vision mode", modifier = Modifier.weight(1f))
                Switch(checked = night == NightTheme.Red, onCheckedChange = onNightChange)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TesterScreen(cameras: List<CameraInfo>, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Camera Tester") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { shareReport(context, buildReport(deviceName(), cameras)) }) {
                        Text("Share")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        if (cameras.isEmpty()) {
            Text("No cameras reported by this device.", modifier = Modifier.padding(padding).padding(20.dp))
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cameras, key = { it.id }) { CameraCard(it) }
            }
        }
    }
}

@Composable
private fun CameraCard(info: CameraInfo) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Camera ${info.id} · ${info.facing}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            info.rows().forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(label, modifier = Modifier.weight(0.4f), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(value, modifier = Modifier.weight(0.6f),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun shareReport(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Astro Camera compatibility report")
    }
    context.startActivity(Intent.createChooser(send, null))
}
