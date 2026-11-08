package com.astrocam.app.camera

import android.content.Context
import android.content.SharedPreferences

enum class OriginalsPolicy(val label: String, val detail: String) {
    Ask("Ask each time", "A dialog appears when a stacked burst starts"),
    Keep("Always keep", "Original frames stay in the gallery"),
    Delete("Always delete", "Originals are deleted after the stacked image is saved and checked"),
}

/** When frames of a burst are stacked: -1 never, 0 once after the last frame, N every N frames. */
object StackPlan {
    val presets = listOf(-1, 0, 5, 10, 20)

    fun label(batch: Int): String = when {
        batch < 0 -> "Off"
        batch == 0 -> "After the burst"
        else -> "Every $batch frames"
    }

    fun short(batch: Int): String = when {
        batch < 0 -> "Off"
        batch == 0 -> "After"
        else -> "Each $batch"
    }
}

enum class TempPreset(val label: String, val warm: Float, val hot: Float, val stop: Float) {
    Cautious("Cautious", 38f, 41f, 43f),
    Normal("Normal", 40f, 43f, 45f),
    Relaxed("Relaxed", 42f, 45f, 47f),
}

data class AppSettings(
    val redMode: Boolean = false,
    val frameCount: Int = 1,
    val timerSeconds: Int = 0,
    val stackBatch: Int = -1,
    val deleteOriginals: Boolean = false,
    val askOriginals: Boolean = false,
    val boost: Boolean = false,
    val liveStacking: Boolean = false,
    val trailFrames: Int = 3,
    val trailInterval: Int = 10,
    val trailMinutes: Int = 30,
    val tempProtection: Boolean = true,
    val tempPreset: TempPreset = TempPreset.Normal,
) {
    /** What happens to original frames after a verified stack. */
    val originals: OriginalsPolicy
        get() = when {
            askOriginals -> OriginalsPolicy.Ask
            deleteOriginals -> OriginalsPolicy.Delete
            else -> OriginalsPolicy.Keep
        }
}

object SettingsStore {
    private const val PREFS = "app_settings"

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, default: T): T =
        runCatching { enumValueOf<T>(getString(key, default.name) ?: default.name) }.getOrDefault(default)

    fun load(context: Context): AppSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = AppSettings()
        val legacy = p.getString("originals", null)
        return AppSettings(
            redMode = p.getBoolean("red_mode", d.redMode),
            frameCount = p.getInt("frame_count", d.frameCount),
            timerSeconds = p.getInt("timer_seconds", d.timerSeconds),
            stackBatch = p.getInt("stack_batch", d.stackBatch),
            deleteOriginals = if (p.contains("delete_originals")) p.getBoolean("delete_originals", false) else legacy == "Delete",
            askOriginals = if (p.contains("ask_originals")) p.getBoolean("ask_originals", false) else legacy == "Ask",
            boost = p.getBoolean("boost_preview", d.boost),
            liveStacking = p.getBoolean("live_stacking", d.liveStacking),
            trailFrames = p.getInt("trail_frames", d.trailFrames),
            trailInterval = p.getInt("trail_interval", d.trailInterval),
            trailMinutes = p.getInt("trail_minutes", d.trailMinutes),
            tempProtection = p.getBoolean("temp_protection", d.tempProtection),
            tempPreset = p.enum("temp_preset", d.tempPreset),
        )
    }

    fun save(context: Context, s: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("red_mode", s.redMode)
            .putInt("frame_count", s.frameCount)
            .putInt("timer_seconds", s.timerSeconds)
            .putInt("stack_batch", s.stackBatch)
            .putBoolean("delete_originals", s.deleteOriginals)
            .putBoolean("ask_originals", s.askOriginals)
            .putBoolean("boost_preview", s.boost)
            .putBoolean("live_stacking", s.liveStacking)
            .putInt("trail_frames", s.trailFrames)
            .putInt("trail_interval", s.trailInterval)
            .putInt("trail_minutes", s.trailMinutes)
            .putBoolean("temp_protection", s.tempProtection)
            .putString("temp_preset", s.tempPreset.name)
            .apply()
    }
}
