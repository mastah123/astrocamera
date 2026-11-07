package com.astrocam.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astrocam.app.camera.AppSettings
import com.astrocam.app.camera.CameraInfo
import com.astrocam.app.camera.CaptureLog
import com.astrocam.app.camera.DeviceProfile
import com.astrocam.app.camera.RecentCapture
import com.astrocam.app.camera.StackPlan
import com.astrocam.app.camera.Thermal
import com.astrocam.app.camera.assessReadiness
import com.astrocam.app.camera.bestBackLimit
import com.astrocam.app.camera.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home is grouped by what you are doing: shoot (Camera, Star Trails), process (Stacking Studio),
 * and check the phone (status and tests).
 */
@Composable
fun HomeScreen(
    cameras: List<CameraInfo>,
    profile: DeviceProfile,
    settings: AppSettings,
    onOpenAstro: () -> Unit,
    onOpenTrails: () -> Unit,
    onOpenStack: () -> Unit,
    onOpenTester: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val readiness = remember(cameras) { assessReadiness(cameras) }
    val best = remember(profile) { profile.bestBackLimit() }
    var recent by remember { mutableStateOf<RecentCapture.Item?>(null) }
    var captures by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val (item, count) = withContext(Dispatchers.IO) {
            RecentCapture.latest(context) to CaptureLog.sessions(context).size
        }
        recent = item
        captures = count
    }
    val thermal by rememberThermal(5000L)
    val rawStates = profile.cameras.filter { it.facing == "Back" }.mapNotNull { it.rawVerified }
    val rawText = when {
        rawStates.any { it } -> "Passed"
        rawStates.isNotEmpty() -> "Failed"
        else -> "Not tested"
    }
    val cardColors = CardDefaults.cardColors(containerColor = colors.surface)

    Scaffold(containerColor = colors.background) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Astro Camera", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Version ${profile.appVersion.ifEmpty { "unknown" }}", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                TempChip(thermal, Thermal.level(thermal, settings.tempPreset)) {}
                TextButton(onClick = onOpenSettings) { Text("⚙ Settings") }
            }

            // Shoot
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAstro), colors = cardColors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            modifier = Modifier.size(72.dp).clip(shape).border(1.dp, colors.outline, shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            val item = recent
                            if (item != null) {
                                Image(item.thumb.asImageBitmap(), "Newest photo", Modifier.size(72.dp), contentScale = ContentScale.Crop)
                            } else {
                                Text("●", style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("CAMERA", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(readiness.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                best?.let { "Exposure ${it.describe()}" } ?: "Exposure not tested yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    Button(onClick = onOpenAstro, modifier = Modifier.fillMaxWidth()) { Text("Open camera") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("★", "Star Trails", "${settings.trailMinutes} min · every ${if (settings.trailInterval == 0) "frame" else "${settings.trailInterval} s"}",
                    Modifier.weight(1f), cardColors, onOpenTrails)
                Tile("▣", "Stacking Studio", if (captures == 0) "No captures yet" else "$captures capture${if (captures == 1) "" else "s"} ready",
                    Modifier.weight(1f), cardColors, onOpenStack)
            }

            // Check the phone
            Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PHONE CHECK", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    StatusRow("Manual control", readiness.title)
                    StatusRow("Exposure", best?.describe() ?: "Not tested")
                    StatusRow("RAW capture", rawText)
                    StatusRow("Live stacking", profile.liveTest?.verdict ?: "Not tested")
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenTester, modifier = Modifier.weight(1f)) { Text("Camera Tester") }
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Run tests") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(
    glyph: String,
    title: String,
    status: String,
    modifier: Modifier,
    cardColors: androidx.compose.material3.CardColors,
    onClick: () -> Unit,
) {
    Card(modifier = modifier.clickable(onClick = onClick), colors = cardColors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(glyph, style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}
