package com.astrocam.app.camera

import android.content.Context
import android.content.SharedPreferences

enum class OriginalsPolicy(val label: String, val detail: String) {
    Ask("Ask each time", "A dialog appears when a stacked burst starts"),
    Keep("Always keep", "Original frames stay in the gallery"),
    Delete("Always delete", "Originals are deleted after the stacked image is saved and checked"),
}

/** When frames of a burst are stacked. Batch 0 means once, after the last frame. */
enum class StackWhile(val label: String, val short: String, val batch: Int) {
    Off("Off", "Off", -1),
    AfterBurst("After the burst", "After", 0),
    Every5("Every 5 frames", "Every 5", 5),
    Every10("Every 10 frames", "Every 10", 10),
    Every20("Every 20 frames", "Every 20", 20),
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
    val stackWhile: StackWhile = StackWhile.Off,
    val autoMethod: StackMode = StackMode.Average,
    val originals: OriginalsPolicy = OriginalsPolicy.Ask,
    val boost: Boolean = true,
    val tempProtection: Boolean = true,
    val tempPreset: TempPreset = TempPreset.Normal,
)

object SettingsStore {
    private const val PREFS = "app_settings"

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, default: T): T =
        runCatching { enumValueOf<T>(getString(key, default.name) ?: default.name) }.getOrDefault(default)

    fun load(context: Context): AppSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = AppSettings()
        return AppSettings(
            redMode = p.getBoolean("red_mode", d.redMode),
            frameCount = p.getInt("frame_count", d.frameCount),
            timerSeconds = p.getInt("timer_seconds", d.timerSeconds),
            stackWhile = p.enum("stack_while", d.stackWhile),
            autoMethod = p.enum("auto_method", d.autoMethod),
            originals = p.enum("originals", d.originals),
            boost = p.getBoolean("boost", d.boost),
            tempProtection = p.getBoolean("temp_protection", d.tempProtection),
            tempPreset = p.enum("temp_preset", d.tempPreset),
        )
    }

    fun save(context: Context, s: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("red_mode", s.redMode)
            .putInt("frame_count", s.frameCount)
            .putInt("timer_seconds", s.timerSeconds)
            .putString("stack_while", s.stackWhile.name)
            .putString("auto_method", s.autoMethod.name)
            .putString("originals", s.originals.name)
            .putBoolean("boost", s.boost)
            .putBoolean("temp_protection", s.tempProtection)
            .putString("temp_preset", s.tempPreset.name)
            .apply()
    }
}
