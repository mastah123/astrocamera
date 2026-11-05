package com.astrocam.app.camera

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

data class ExposureStep(val requestedNs: Long, val appliedNs: Long?, val honored: Boolean)

data class ProbeResult(
    val cameraId: String,
    val steps: List<ExposureStep>,
    val isoRequested: Int?,
    val isoApplied: Int?,
    val autoBoostNs: Long?,
    val error: String? = null,
)

val ProbeResult.manualHonored: Boolean get() = steps.count { it.honored } >= 2
val ProbeResult.maxHonoredNs: Long get() = steps.filter { it.honored }.maxOfOrNull { it.requestedNs } ?: 0L
val ProbeResult.isoHonored: Boolean
    get() = isoRequested != null && isoApplied != null &&
        kotlin.math.abs(isoApplied - isoRequested) <= isoRequested * 0.1

enum class ExposurePath(val title: String) {
    Hardware("Hardware long exposure works"),
    Stacking("Manual exposure works, stack to go longer"),
    AutoBoost("Manual ignored, stack boosted auto frames"),
    None("No usable long exposure found"),
}

data class PlanRow(val targetSeconds: Int, val frames: Int, val snrGain: Double)

data class Verdict(
    val path: ExposurePath,
    val frameNs: Long,
    val summary: String,
    val rows: List<PlanRow>,
)

private val TARGETS_SECONDS = listOf(10, 30, 60)

fun assess(result: ProbeResult): Verdict {
    val boost = result.autoBoostNs
    val (path, frameNs, summary) = when {
        result.error != null -> Triple(ExposurePath.None, 0L, result.error)
        result.manualHonored && result.maxHonoredNs >= 1_000_000_000L -> Triple(
            ExposurePath.Hardware, result.maxHonoredNs,
            "The camera honored manual exposure up to ${formatExposure(result.maxHonoredNs)}. " +
                "Longer totals can be reached by stacking frames."
        )
        result.manualHonored -> Triple(
            ExposurePath.Stacking, result.maxHonoredNs,
            "The camera honored manual exposure only up to ${formatExposure(result.maxHonoredNs)}. " +
                "Software stacking can simulate a longer exposure."
        )
        boost != null && boost >= 50_000_000L -> Triple(
            ExposurePath.AutoBoost, boost,
            "Manual exposure is ignored, but auto exposure with maximum boost reached " +
                "${formatExposure(boost)}. Stack many such frames. Run this test in the dark for a true value."
        )
        else -> Triple(
            ExposurePath.None, 0L,
            "This camera did not honor manual exposure and auto exposure stayed short. " +
                "Stacking short frames still reduces noise but gains little light."
        )
    }
    val rows = if (frameNs > 0) TARGETS_SECONDS.map { target ->
        val frames = ceil(target / (frameNs / 1e9)).toInt().coerceAtLeast(1)
        PlanRow(target, frames, sqrt(frames.toDouble()))
    } else emptyList()
    return Verdict(path, frameNs, summary, rows)
}

fun probeReport(device: String, results: List<ProbeResult>): String = buildString {
    appendLine("Astro Camera - Long Exposure Test")
    appendLine("Device: $device")
    results.forEach { r ->
        val v = assess(r)
        appendLine()
        appendLine("Camera ${r.cameraId}: ${v.path.title}")
        appendLine("  ${v.summary}")
        r.steps.forEach { s ->
            val got = s.appliedNs?.let(::formatExposure) ?: "no result"
            appendLine("  Requested ${formatExposure(s.requestedNs)} -> got $got ${if (s.honored) "OK" else "NOT HONORED"}")
        }
        if (r.isoRequested != null) {
            appendLine("  ISO requested ${r.isoRequested} -> got ${r.isoApplied ?: "n/a"}")
        }
        r.autoBoostNs?.let { appendLine("  Auto exposure at max boost: ${formatExposure(it)}") }
        v.rows.forEach {
            appendLine("  ${it.targetSeconds} s total -> ${it.frames} frames, noise x${"%.1f".format(Locale.US, it.snrGain)} better")
        }
    }
}
