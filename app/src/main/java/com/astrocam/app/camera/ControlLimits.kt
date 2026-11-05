package com.astrocam.app.camera

import kotlin.math.exp
import kotlin.math.ln

/** What the manual controls may offer for one camera, based on the verified profile. */
data class ControlLimits(
    val camera: CameraInfo,
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val exposureVerified: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val manualAvailable: Boolean,
    val manualFocus: Boolean,
    val minFocusDiopters: Float,
)

// Until the Long Exposure Test proves a limit, stay at a safe 1/30 s.
private const val UNVERIFIED_CAP_NS = 33_333_333L

fun controlLimits(cameras: List<CameraInfo>, profile: DeviceProfile): ControlLimits? {
    val back = cameras.filter { it.facing == "Back" }
    if (back.isEmpty()) return null
    fun verified(c: CameraInfo) = profile.cameras.firstOrNull { it.cameraId == c.id }?.verified

    val camera = back.maxWithOrNull(
        compareBy({ verified(it)?.verification?.rank ?: 0 }, { verified(it)?.maxNs ?: 0L })
    ) ?: back.first()
    val exposure = camera.exposureRangeNs
    val iso = camera.isoRange
    val proven = verified(camera)?.takeIf { it.verification.rank >= 2 }

    val max = proven?.maxNs ?: minOf(exposure?.endInclusive ?: UNVERIFIED_CAP_NS, UNVERIFIED_CAP_NS)
    val min = (exposure?.start ?: 1_000_000L).coerceAtMost(max)
    return ControlLimits(
        camera = camera,
        minExposureNs = min,
        maxExposureNs = max,
        exposureVerified = proven != null,
        isoMin = iso?.start ?: 100,
        isoMax = iso?.endInclusive ?: 100,
        manualAvailable = exposure != null && iso != null,
        manualFocus = camera.manualFocus,
        minFocusDiopters = camera.minFocusDiopters,
    )
}

private fun logMap(frac: Float, lo: Double, hi: Double): Double =
    if (hi <= lo) lo else exp(ln(lo) + frac.coerceIn(0f, 1f) * (ln(hi) - ln(lo)))

fun ControlLimits.exposureAt(frac: Float): Long =
    logMap(frac, minExposureNs.toDouble(), maxExposureNs.toDouble()).toLong()
        .coerceIn(minExposureNs, maxExposureNs)

fun ControlLimits.isoAt(frac: Float): Int =
    logMap(frac, isoMin.toDouble(), isoMax.toDouble()).toInt().coerceIn(isoMin, isoMax)

fun ControlLimits.isoFraction(iso: Int): Float =
    if (isoMax <= isoMin) 0f
    else ((ln(iso.coerceIn(isoMin, isoMax).toDouble()) - ln(isoMin.toDouble())) /
        (ln(isoMax.toDouble()) - ln(isoMin.toDouble()))).toFloat()
