package com.astrocam.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** A marked position on a ruler. [frac] runs from 0 to 1 along the slider. */
class Snap(val frac: Float, val label: String)

private const val SNAP_RANGE = 0.025f

/**
 * A slider with a ruler under it. It slides freely, and only pulls onto a marked position
 * when the thumb is close to one, with a small tick of feedback.
 */
@Composable
fun SnapSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    snaps: List<Snap>,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var lastSnap by remember { mutableStateOf<Int?>(null) }
    Column(modifier) {
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = { raw ->
                val near = snaps.withIndex().minByOrNull { abs(it.value.frac - raw) }
                if (near != null && abs(near.value.frac - raw) <= SNAP_RANGE) {
                    if (lastSnap != near.index) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastSnap = near.index
                    }
                    onValueChange(near.value.frac)
                } else {
                    lastSnap = null
                    onValueChange(raw)
                }
            },
        )
        Ruler(snaps)
    }
}

@Composable
private fun Ruler(snaps: List<Snap>) {
    val colors = MaterialTheme.colorScheme
    val minor = colors.outline
    val major = colors.onSurfaceVariant
    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
        // The slider track starts and ends half a thumb in from each side.
        val inset = 10.dp
        val usable = maxWidth - inset * 2
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val pad = inset.toPx()
            val width = size.width - pad * 2
            for (i in 0..20) {
                val x = pad + width * i / 20f
                drawLine(minor, Offset(x, 0f), Offset(x, if (i % 5 == 0) 6.dp.toPx() else 3.dp.toPx()), 1.dp.toPx())
            }
            snaps.forEach { s ->
                val x = pad + width * s.frac
                drawLine(major, Offset(x, 0f), Offset(x, 8.dp.toPx()), 2.dp.toPx())
            }
        }
        snaps.forEach { s ->
            Box(
                modifier = Modifier.offset(x = inset + usable * s.frac - 20.dp).width(40.dp).padding(top = 10.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = major,
                    maxLines = 1,
                )
            }
        }
    }
}
