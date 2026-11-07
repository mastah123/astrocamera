package com.astrocam.app.camera

import android.os.SystemClock
import kotlin.math.roundToInt

/** Learns how fast a stack is going and estimates how long is left. */
class ProgressTracker {
    private var start = 0L

    fun begin() {
        start = SystemClock.elapsedRealtime()
    }

    /** Seconds left, or null until enough work is done to guess. */
    fun etaSeconds(fraction: Float): Int? {
        if (start == 0L || fraction < 0.05f || fraction >= 1f) return null
        val elapsed = SystemClock.elapsedRealtime() - start
        return (elapsed / fraction * (1f - fraction) / 1000f).roundToInt()
    }
}

fun formatEta(seconds: Int): String = if (seconds < 90) "$seconds s" else "${(seconds + 59) / 60} min"

/** For example "Stacking 12 of 20 · 45% · about 8 s left". */
fun formatProgress(label: String, fraction: Float, etaSeconds: Int?): String {
    val percent = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
    return buildString {
        append(label)
        append(" · $percent%")
        if (etaSeconds != null) append(" · about ${formatEta(etaSeconds)} left")
    }
}
