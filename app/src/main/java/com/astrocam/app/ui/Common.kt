package com.astrocam.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astrocam.app.camera.Thermal
import com.astrocam.app.camera.ThermalLevel
import com.astrocam.app.camera.ThermalReading
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun OptionRow(
    selected: Boolean,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
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

/** Heat readings that refresh every couple of seconds while the screen is shown. */
@Composable
internal fun rememberThermal(intervalMs: Long = 2000L): State<ThermalReading> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Thermal.read(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            state.value = withContext(Dispatchers.IO) { Thermal.read(context) }
        }
    }
    return state
}

@Composable
internal fun TempChip(reading: ThermalReading, level: ThermalLevel, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val hot = level >= ThermalLevel.Hot
    val symbol = when {
        hot -> "⚠ "
        level == ThermalLevel.Warm -> "▲ "
        else -> ""
    }
    val temp = reading.batteryC?.let { "%.0f°C".format(Locale.US, it) } ?: "—"
    Text(
        text = symbol + (if (reading.charging) "⚡" else "") + temp,
        style = MaterialTheme.typography.labelLarge,
        color = if (hot) colors.onPrimary else colors.onBackground,
        modifier = Modifier
            .clip(shape)
            .background(if (hot) colors.primary else Color.Transparent)
            .border(1.dp, if (level == ThermalLevel.Normal) colors.outline else colors.primary, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
