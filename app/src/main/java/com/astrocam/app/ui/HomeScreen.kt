package com.astrocam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astrocam.app.camera.CameraInfo
import com.astrocam.app.camera.DeviceProfile
import com.astrocam.app.camera.assessReadiness
import com.astrocam.app.camera.bestBackLimit
import com.astrocam.app.camera.describe
import com.astrocam.app.camera.formatExposure

@Composable
fun HomeScreen(
    cameras: List<CameraInfo>,
    profile: DeviceProfile,
    onOpenAstro: () -> Unit,
    onOpenStack: () -> Unit,
    onOpenTester: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val readiness = remember(cameras) { assessReadiness(cameras) }
    val back = remember(cameras) { cameras.filter { it.facing == "Back" } }
    val best = remember(profile) { profile.bestBackLimit() }
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
                        Text("Reported exposure limit: ${maxExposure?.let(::formatExposure) ?: "n/a"}")
                        Text("Max ISO: ${maxIso ?: "n/a"}")
                        Text("RAW / DNG: ${if (back.any { it.raw }) "Yes" else "No"}")
                    }
                    if (best != null) {
                        val label = if (best.verification.rank >= 2) "Verified exposure" else "Unverified exposure"
                        Text("$label: ${best.describe()}", fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Verified exposure: not tested yet", color = colors.onSurfaceVariant)
                    }
                }
            }

            Button(onClick = onOpenAstro, modifier = Modifier.fillMaxWidth()) {
                Text("Astro Camera")
            }
            OutlinedButton(onClick = onOpenStack, modifier = Modifier.fillMaxWidth()) {
                Text("Stacking Studio")
            }
            OutlinedButton(onClick = onOpenTester, modifier = Modifier.fillMaxWidth()) {
                Text("Camera Tester")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
        }
    }
}
