package com.astrocam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astrocam.app.camera.DeviceProfile
import com.astrocam.app.camera.appBuild
import com.astrocam.app.camera.describe
import com.astrocam.app.camera.formatExposure
import com.astrocam.app.camera.toJson

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    night: NightTheme,
    onNightChange: (Boolean) -> Unit,
    profile: DeviceProfile,
    onResetProfile: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenRaw: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Astro Camera", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Version ${profile.appVersion.ifEmpty { "unknown" }} (build ${appBuild(context)})",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Text("Appearance", fontWeight = FontWeight.SemiBold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Red night-vision mode", modifier = Modifier.weight(1f))
                    Switch(checked = night == NightTheme.Red, onCheckedChange = onNightChange)
                }
            }

            Text("Device profile", fontWeight = FontWeight.SemiBold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile.device, color = colors.onSurfaceVariant)
                    profile.cameras.forEach { c ->
                        Text("Camera ${c.cameraId} · ${c.facing}", fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp))
                        Text("Reported exposure: ${c.reportedMaxExposureNs?.let(::formatExposure) ?: "n/a"}")
                        Text("Verified exposure: ${c.verified?.describe() ?: "not tested"}")
                        Text("RAW capture: ${when (c.rawVerified) { true -> "passed"; false -> "failed"; null -> "not tested" }}")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { shareReport(context, profile.toJson(), "Astro Camera device profile") },
                            modifier = Modifier.weight(1f),
                        ) { Text("Share profile") }
                        OutlinedButton(
                            onClick = {
                                if (confirmReset) { onResetProfile(); confirmReset = false } else confirmReset = true
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (confirmReset) "Tap to confirm" else "Reset limits") }
                    }
                }
            }

            Text("Experimental", fontWeight = FontWeight.SemiBold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "These tools push the camera hardware to its limits. Results vary by phone " +
                            "and tests can be slow.",
                        color = colors.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpenProbe, modifier = Modifier.fillMaxWidth()) {
                        Text("Long Exposure Test")
                    }
                    OutlinedButton(onClick = onOpenRaw, modifier = Modifier.fillMaxWidth()) {
                        Text("RAW Capture Test")
                    }
                }
            }

        }
    }
}
