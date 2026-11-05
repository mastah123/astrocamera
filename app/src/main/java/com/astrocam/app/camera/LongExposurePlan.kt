package com.astrocam.app.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

data class ExposureStep(
    val requestedNs: Long,
    val appliedNs: Long?,
    val honored: Boolean,
    val luma: Double? = null,
    val elapsedMs: Long? = null,
    /** Why a metadata match was rejected as not a real exposure, if it was. */
    val echoReason: String? = null,
    val frameDurationNs: Long? = null,
) {
    val echoed: Boolean get() = echoReason != null
}

fun ExposureStep.timing(): String {
    val took = if (requestedNs >= 1_000_000_000L && elapsedMs != null)
        "  took %.1f s".format(Locale.US, elapsedMs / 1000.0) else ""
    val frame = if (requestedNs >= 100_000_000L && frameDurationNs != null)
        "  frame ${formatExposure(frameDurationNs)}" else ""
    return took + frame
}

fun PlanRow.framesLabel(): String = if (frames == 1) "1 frame" else "$frames frames"


data class ProbeResult(
    val cameraId: String,
    val steps: List<ExposureStep>,
    val isoRequested: Int?,
    val isoApplied: Int?,
    val autoBoostNs: Long?,
    val error: String? = null,
)

val ProbeResult.manualHonored: Boolean get() = steps.count { it.honored } >= 2
val ProbeResult.maxHonoredNs: Long
    get() = steps.filter { it.honored }.maxOfOrNull { it.requestedNs } ?: 0L
val ProbeResult.hitTestCeiling: Boolean get() = steps.isNotEmpty() && steps.all { it.honored }
val ProbeResult.firstMissNs: Long? get() = steps.firstOrNull { !it.honored }?.requestedNs

enum class BrightnessStatus { Confirmed, Inconclusive, NoData }

data class BrightnessCheck(val status: BrightnessStatus, val detail: String)

/**
 * Checks that longer exposures really produce brighter frames, not just matching metadata.
 * YUV white is about 235, so anything above 230 is treated as clipped.
 */
fun ProbeResult.brightnessCheck(): BrightnessCheck {
    val lumas = steps.filter { it.honored }.mapNotNull { it.luma }
    if (lumas.isEmpty()) return BrightnessCheck(BrightnessStatus.NoData, "No frame data was read.")
    val usable = lumas.filter { it < 230.0 }
    if (usable.size < 2) {
        return BrightnessCheck(BrightnessStatus.Inconclusive, "Scene too bright, frames clipped. Try a dimmer scene.")
    }
    val range = "%.0f → %.0f".format(Locale.US, usable.first(), usable.last())
    val steady = usable.zipWithNext().all { (a, b) -> b >= a - 5.0 }
    return when {
        !steady -> BrightnessCheck(
            BrightnessStatus.Inconclusive,
            "Brightness went up and down (luma ${usable.joinToString("/") { "%.0f".format(Locale.US, it) }}). " +
                "Lighting changed, the phone moved, or exposure is not being applied. Try a dimmer, steadier scene."
        )
        usable.last() - usable.first() >= 3.0 -> BrightnessCheck(
            BrightnessStatus.Confirmed, "Frames got steadily brighter with longer exposure (luma $range)."
        )
        else -> BrightnessCheck(
            BrightnessStatus.Inconclusive, "Frames did not get clearly brighter (luma $range). The scene may be too dark."
        )
    }
}

val ProbeResult.isoHonored: Boolean
    get() = isoRequested != null && isoApplied != null &&
        abs(isoApplied - isoRequested) <= isoRequested * 0.1

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

private val TARGETS_SECONDS = listOf(10, 30, 60, 300)

private fun ceilingNote(r: ProbeResult): String {
    val miss = r.steps.firstOrNull { !it.honored }
    return when {
        r.hitTestCeiling -> "This was the highest value tested, so the real limit may be higher."
        miss != null && miss.echoReason != null ->
            "The camera reported ${formatExposure(miss.requestedNs)} but ${miss.echoReason}."
        miss != null -> "The camera did not honor ${formatExposure(miss.requestedNs)}."
        else -> ""
    }
}

fun assess(result: ProbeResult): Verdict {
    val boost = result.autoBoostNs
    val (path, frameNs, summary) = when {
        result.error != null -> Triple(ExposurePath.None, 0L, result.error)
        result.manualHonored && result.maxHonoredNs >= 1_000_000_000L -> Triple(
            ExposurePath.Hardware, result.maxHonoredNs,
            "The camera honored manual exposure up to ${formatExposure(result.maxHonoredNs)}. " +
                "${ceilingNote(result)} Longer totals can be reached by stacking frames."
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
            val luma = s.luma?.let { " luma %.0f".format(Locale.US, it) } ?: ""
            val verdictText = when { s.honored -> "OK"; s.echoed -> "ECHOED (${s.echoReason})"; else -> "NOT HONORED" }
            appendLine("  Requested ${formatExposure(s.requestedNs)} -> got $got $verdictText$luma${s.timing()}")
        }
        if (r.manualHonored) appendLine("  Brightness check: ${r.brightnessCheck().status} - ${r.brightnessCheck().detail}")
        if (r.isoRequested != null) {
            appendLine("  ISO requested ${r.isoRequested} -> got ${r.isoApplied ?: "n/a"}")
        }
        r.autoBoostNs?.let { appendLine("  Auto exposure at max boost: ${formatExposure(it)}") }
        v.rows.forEach {
            appendLine("  ${it.targetSeconds} s total -> ${it.framesLabel()}, noise x${"%.1f".format(Locale.US, it.snrGain)} better")
        }
    }
}
