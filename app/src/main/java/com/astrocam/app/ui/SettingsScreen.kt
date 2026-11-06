package com.astrocam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.astrocam.app.camera.AppSettings
import com.astrocam.app.camera.CrashLog
import com.astrocam.app.camera.DeviceProfile
import com.astrocam.app.camera.OriginalsPolicy
import com.astrocam.app.camera.StackMode
import com.astrocam.app.camera.StackWhile
import com.astrocam.app.camera.TempPreset
import com.astrocam.app.camera.Thermal
import com.astrocam.app.camera.appBuild
import com.astrocam.app.camera.describe
import com.astrocam.app.camera.formatExposure
import com.astrocam.app.camera.toJson

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    profile: DeviceProfile,
    onResetProfile: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenRaw: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var confirmReset by remember { mutableStateOf(false) }
    var confirmProtectionOff by remember { mutableStateOf(false) }
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    val thermal by rememberThermal()

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard {
                Text("Astro Camera", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Version ${profile.appVersion.ifEmpty { "unknown" }} (build ${appBuild(context)})",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            SectionTitle("Appearance")
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Red night-vision mode", modifier = Modifier.weight(1f))
                    Switch(checked = settings.redMode, onCheckedChange = { onSettings(settings.copy(redMode = it)) })
                }
            }

            SectionTitle("Stack while capturing")
            SettingsCard {
                StackWhile.values().forEach { option ->
                    OptionRow(
                        selected = settings.stackWhile == option,
                        title = option.label,
                        detail = when (option) {
                            StackWhile.Off -> "Frames are only saved"
                            StackWhile.AfterBurst -> "Stack once, after the last frame"
                            else -> "Stack each batch, then keep shooting. Batches are combined at the end"
                        },
                    ) { onSettings(settings.copy(stackWhile = option)) }
                }
                Text("Method", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                StackMode.values().forEach { m ->
                    OptionRow(settings.autoMethod == m, m.label, m.hint) { onSettings(settings.copy(autoMethod = m)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Brightness boost")
                        Text("Brightens faint detail in the final stack", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    Switch(checked = settings.boost, onCheckedChange = { onSettings(settings.copy(boost = it)) })
                }
            }

            SectionTitle("Original frames after stacking")
            SettingsCard {
                OriginalsPolicy.values().forEach { policy ->
                    OptionRow(settings.originals == policy, policy.label, policy.detail) {
                        onSettings(settings.copy(originals = policy))
                    }
                }
            }

            SectionTitle("Safety")
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Temperature protection")
                        Text("Warns, pauses and stops a burst before the phone overheats",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.tempProtection,
                        onCheckedChange = { on ->
                            if (on) onSettings(settings.copy(tempProtection = true)) else confirmProtectionOff = true
                        },
                    )
                }
                if (settings.tempProtection) {
                    TempPreset.values().forEach { preset ->
                        OptionRow(
                            selected = settings.tempPreset == preset,
                            title = preset.label,
                            detail = "Warn ${preset.warm.toInt()}°C · Pause ${preset.hot.toInt()}°C · Stop ${preset.stop.toInt()}°C",
                        ) { onSettings(settings.copy(tempPreset = preset)) }
                    }
                }
            }

            SectionTitle("Device profile")
            SettingsCard {
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
                        onClick = { if (confirmReset) { onResetProfile(); confirmReset = false } else confirmReset = true },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (confirmReset) "Tap to confirm" else "Reset limits") }
                }
            }

            SectionTitle("Experimental")
            SettingsCard {
                Text(
                    "These tools push the camera hardware to its limits. Results vary by phone and tests can be slow.",
                    color = colors.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenProbe, modifier = Modifier.fillMaxWidth()) { Text("Long Exposure Test") }
                OutlinedButton(onClick = onOpenRaw, modifier = Modifier.fillMaxWidth()) { Text("RAW Capture Test") }
            }

            SectionTitle("Diagnostics")
            SettingsCard {
                Text(Thermal.summary(thermal), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (crash == null) "Last crash: none recorded" else "Last crash: recorded. Share it so it can be fixed.",
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { crash?.let { shareReport(context, it, "Astro Camera crash log") } },
                        enabled = crash != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Share last crash") }
                    OutlinedButton(
                        onClick = { CrashLog.clear(context); crash = null },
                        enabled = crash != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear") }
                }
            }
        }
    }

    if (confirmProtectionOff) {
        AlertDialog(
            onDismissRequest = { confirmProtectionOff = false },
            title = { Text("Turn off temperature protection?") },
            text = {
                Text("Long bursts and stacking heat the phone. Without protection, a burst will not pause or stop when it gets hot.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmProtectionOff = false
                    onSettings(settings.copy(tempProtection = false))
                }) { Text("Turn off") }
            },
            dismissButton = { TextButton(onClick = { confirmProtectionOff = false }) { Text("Keep on") } },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
