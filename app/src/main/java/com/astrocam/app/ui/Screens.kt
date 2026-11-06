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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astrocam.app.camera.CameraInfo
import com.astrocam.app.camera.CameraProfile
import com.astrocam.app.camera.DeviceProfile
import com.astrocam.app.camera.Thermal
import com.astrocam.app.camera.buildReport
import com.astrocam.app.camera.deviceName
import com.astrocam.app.camera.rawRow
import com.astrocam.app.camera.rows
import com.astrocam.app.camera.verifiedRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TesterScreen(cameras: List<CameraInfo>, profile: DeviceProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Camera Tester") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { shareReport(context, buildReport(deviceName(), cameras, profile, Thermal.read(context))) }) {
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
                items(cameras, key = { it.id }) { camera ->
                    CameraCard(camera, profile.cameras.firstOrNull { it.cameraId == camera.id })
                }
            }
        }
    }
}

@Composable
private fun CameraCard(info: CameraInfo, profile: CameraProfile?) {
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
            (info.rows() + verifiedRow(profile?.verified) + rawRow(profile?.rawVerified)).forEach { (label, value) ->
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

internal fun shareReport(
    context: Context,
    text: String,
    subject: String = "Astro Camera compatibility report",
) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(send, null))
}
