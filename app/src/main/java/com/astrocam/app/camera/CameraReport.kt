package com.astrocam.app.camera

import android.os.Build
import java.util.Locale
import kotlin.math.roundToInt

enum class Readiness(val title: String, val detail: String) {
    Ready(
        "Ready for manual astrophotography",
        "Manual exposure and ISO control are available on a rear camera."
    ),
    Limited(
        "Limited manual control",
        "Rear camera found, but it does not report manual sensor control. " +
            "Run the Long Exposure Test (Settings, Experimental) to check whether it honors manual settings anyway."
    ),
    Unavailable(
        "No rear camera detected",
        "Camera2 did not report a rear-facing camera on this device."
    ),
}

fun assessReadiness(cameras: List<CameraInfo>): Readiness {
    val back = cameras.filter { it.facing == "Back" }
    return when {
        back.any { it.manualSensor } -> Readiness.Ready
        back.isNotEmpty() -> Readiness.Limited
        else -> Readiness.Unavailable
    }
}

fun formatExposure(ns: Long): String = when {
    ns >= 1_000_000_000L -> "%.1f s".format(Locale.US, ns / 1e9)
    ns > 0L -> "1/${(1e9 / ns).roundToInt()} s"
    else -> "0 s"
}

fun deviceName(): String =
    "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

/** Label/value rows shown in the UI and in the shared text report. */
fun CameraInfo.rows(): List<Pair<String, String>> = listOf(
    "Lens" to facing,
    "Hardware level" to hardwareLevel,
    "Sensor" to "%d × %d (%.1f MP)".format(
        Locale.US, sensorWidth, sensorHeight, sensorWidth * sensorHeight / 1_000_000f
    ),
    "Focal length" to focalLengthsMm.joinToString { "%.1f mm".format(Locale.US, it) }
        .ifEmpty { "Unknown" },
    "Aperture" to apertures.joinToString { "f/%.1f".format(Locale.US, it) }
        .ifEmpty { "Unknown" },
    "Exposure time" to (exposureRangeNs?.let {
        "${formatExposure(it.start)} – ${formatExposure(it.endInclusive)}"
    }?.let { if (manualSensor) it else "$it (reported only)" } ?: "Not adjustable"),
    "ISO" to (isoRange?.let { "${it.start} – ${it.endInclusive}" }
        ?.let { if (manualSensor) it else "$it (reported only)" } ?: "Not adjustable"),
    "Manual sensor control" to yesNo(manualSensor),
    "Manual focus" to when {
        manualFocus -> "Yes (closest focus %.1f cm)".format(Locale.US, 100f / minFocusDiopters)
        minFocusDiopters == 0f -> "No (fixed focus)"
        else -> "No"
    },
    "Infinity focus" to yesNo(manualFocus),
    "White balance" to when {
        manualPostProcessing -> "Manual"
        awbModeCount > 1 -> "Preset modes"
        else -> "Auto only"
    },
    "Exposure compensation" to (aeCompRangeEv?.let {
        "%+.1f to %+.1f EV".format(Locale.US, it.start, it.endInclusive)
    } ?: "Not supported"),
    "RAW / DNG" to when {
        raw -> "Yes"
        formats.any { it.startsWith("RAW_SENSOR") } -> "Unconfirmed (format listed, not advertised)"
        else -> "No"
    },
    "Output formats" to formats.joinToString().ifEmpty { "Unknown" },
    "Logical multi-camera" to if (physicalIds.isEmpty()) "No"
    else "Yes (physical IDs: ${physicalIds.joinToString()})",
)

fun buildReport(device: String, cameras: List<CameraInfo>): String = buildString {
    appendLine("Astro Camera - Compatibility Report")
    appendLine("Device: $device")
    appendLine("Readiness: ${assessReadiness(cameras).title}")
    cameras.forEach { camera ->
        appendLine()
        appendLine("Camera ${camera.id} (${camera.facing})")
        camera.rows().forEach { (label, value) -> appendLine("  $label: $value") }
    }
}
