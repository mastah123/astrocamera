package com.astrocam.app.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

/** What this phone reports about heat. Any value can be null if the phone does not provide it. */
data class ThermalReading(
    val batteryC: Float?,
    val status: Int?,
    val headroom: Float?,
    val charging: Boolean,
)

enum class ThermalLevel { Normal, Warm, Hot, Stop }

object Thermal {
    private var cachedHeadroom: Float? = null
    private var headroomAt = 0L

    fun read(context: Context): ThermalReading {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else null
        return ThermalReading(
            batteryC = if (tenths == Int.MIN_VALUE) null else tenths / 10f,
            status = status,
            headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) headroom(power) else null,
            charging = charging,
        )
    }

    // Android asks apps not to poll this more than about every 10 seconds.
    private fun headroom(power: PowerManager): Float? {
        val now = SystemClock.elapsedRealtime()
        if (now - headroomAt >= 10_000L) {
            headroomAt = now
            cachedHeadroom = runCatching { power.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() }
        }
        return cachedHeadroom
    }

    /** Battery temperature and the system's own thermal status, whichever is worse. */
    fun level(reading: ThermalReading, preset: TempPreset, margin: Float = 0f): ThermalLevel {
        val t = reading.batteryC
        val byTemp = when {
            t == null -> ThermalLevel.Normal
            t >= preset.stop - margin -> ThermalLevel.Stop
            t >= preset.hot - margin -> ThermalLevel.Hot
            t >= preset.warm - margin -> ThermalLevel.Warm
            else -> ThermalLevel.Normal
        }
        val byStatus = when (reading.status ?: 0) {
            0 -> ThermalLevel.Normal
            1 -> ThermalLevel.Warm
            2 -> ThermalLevel.Hot
            else -> ThermalLevel.Stop
        }
        return if (byTemp >= byStatus) byTemp else byStatus
    }

    fun statusName(status: Int?): String = when (status) {
        null -> "not reported"
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        else -> "shutdown"
    }

    fun tempText(reading: ThermalReading): String =
        reading.batteryC?.let { "%.1f°C".format(Locale.US, it) } ?: "n/a"

    fun summary(reading: ThermalReading): String = buildString {
        appendLine("Battery temperature: ${tempText(reading)}")
        appendLine("Thermal status: ${statusName(reading.status)}")
        appendLine("Thermal headroom: ${reading.headroom?.let { "%.2f".format(Locale.US, it) } ?: "not reported"}")
        append("Charging: ${if (reading.charging) "yes" else "no"}")
    }
}
