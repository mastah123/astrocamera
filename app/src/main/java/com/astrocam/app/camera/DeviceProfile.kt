package com.astrocam.app.camera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** How a long exposure limit was proven. Higher rank is stronger evidence. */
enum class Verification(val label: String, val rank: Int) {
    Timing("real elapsed time", 3),
    Timestamps("sensor timestamps", 3),
    Brightness("brightness", 2),
    Metadata("metadata only, unverified", 1),
}

data class VerifiedLimit(val maxNs: Long, val mode: String, val verification: Verification)

fun VerifiedLimit.describe(): String =
    "up to ${formatExposure(maxNs)} ($mode mode, ${verification.label})"

fun verifiedRow(limit: VerifiedLimit?): Pair<String, String> =
    "Verified exposure" to (limit?.describe() ?: "Not tested")

fun ProbeResult.verification(): Verification = when {
    maxHonoredNs >= 1_000_000_000L -> Verification.Timing
    modeLabel == RequestMode.Repeating.label -> Verification.Timestamps
    !darkRoom && brightnessCheck().status == BrightnessStatus.Confirmed -> Verification.Brightness
    else -> Verification.Metadata
}

data class CameraProfile(
    val cameraId: String,
    val facing: String,
    val reportedMaxExposureNs: Long?,
    val isoMin: Int?,
    val isoMax: Int?,
    val rawAdvertised: Boolean,
    val manualFocusReported: Boolean,
    val extensions: List<String>,
    val verified: VerifiedLimit?,
)

data class DeviceProfile(
    val device: String,
    val appVersion: String,
    val cameras: List<CameraProfile>,
)

fun buildProfile(
    device: String,
    appVersion: String,
    cameras: List<CameraInfo>,
    limits: Map<String, VerifiedLimit>,
) = DeviceProfile(
    device, appVersion,
    cameras.map { c ->
        CameraProfile(
            cameraId = c.id,
            facing = c.facing,
            reportedMaxExposureNs = c.exposureRangeNs?.endInclusive,
            isoMin = c.isoRange?.start,
            isoMax = c.isoRange?.endInclusive,
            rawAdvertised = c.raw,
            manualFocusReported = c.manualFocus,
            extensions = c.extensions,
            verified = limits[c.id],
        )
    },
)

/** Strongest, then longest, verified limit among rear cameras. */
fun DeviceProfile.bestBackLimit(): VerifiedLimit? =
    cameras.filter { it.facing == "Back" }.mapNotNull { it.verified }
        .maxWithOrNull(compareBy({ it.verification.rank }, { it.maxNs }))

fun DeviceProfile.toJson(): String = JSONObject().apply {
    put("device", device)
    put("appVersion", appVersion)
    put("cameras", JSONArray().apply {
        cameras.forEach { c ->
            put(JSONObject().apply {
                put("id", c.cameraId)
                put("facing", c.facing)
                put("reportedMaxExposureNs", c.reportedMaxExposureNs ?: JSONObject.NULL)
                put("isoMin", c.isoMin ?: JSONObject.NULL)
                put("isoMax", c.isoMax ?: JSONObject.NULL)
                put("rawAdvertised", c.rawAdvertised)
                put("manualFocusReported", c.manualFocusReported)
                put("extensions", JSONArray(c.extensions))
                put("verifiedMaxExposureNs", c.verified?.maxNs ?: JSONObject.NULL)
                put("verifiedMode", c.verified?.mode ?: JSONObject.NULL)
                put("verifiedBy", c.verified?.verification?.name ?: JSONObject.NULL)
            })
        }
    })
}.toString(2)

fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull().orEmpty()

/** Verified limits kept on the device, keyed by camera id. */
object ProfileStore {
    private const val PREFS = "device_profile"
    private const val KEY = "verified_limits"

    fun load(context: Context): Map<String, VerifiedLimit> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { id ->
                val o = obj.getJSONObject(id)
                VerifiedLimit(o.getLong("ns"), o.getString("mode"), Verification.valueOf(o.getString("v")))
            }
        }.getOrDefault(emptyMap())
    }

    /** Keeps stronger evidence first, then the longer exposure. Returns what is stored. */
    fun merge(context: Context, results: List<ProbeResult>): Map<String, VerifiedLimit> {
        val limits = load(context).toMutableMap()
        results.filter { it.manualHonored }.forEach { r ->
            val candidate = VerifiedLimit(r.maxHonoredNs, r.modeLabel, r.verification())
            val current = limits[r.cameraId]
            val better = current == null ||
                candidate.verification.rank > current.verification.rank ||
                (candidate.verification.rank == current.verification.rank && candidate.maxNs > current.maxNs)
            if (better) limits[r.cameraId] = candidate
        }
        save(context, limits)
        return limits
    }

    fun reset(context: Context): Map<String, VerifiedLimit> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        return emptyMap()
    }

    private fun save(context: Context, limits: Map<String, VerifiedLimit>) {
        val obj = JSONObject()
        limits.forEach { (id, l) ->
            obj.put(id, JSONObject().put("ns", l.maxNs).put("mode", l.mode).put("v", l.verification.name))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, obj.toString()).apply()
    }
}
