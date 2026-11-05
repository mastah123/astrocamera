package com.astrocam.app.camera

import android.content.Context

/** Best long exposure limit confirmed by the test on this device. */
data class TestedLimit(val maxNs: Long, val brightnessConfirmed: Boolean)

object ProbeStore {
    private const val PREFS = "probe"

    fun load(context: Context): TestedLimit? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ns = p.getLong("verified_max_ns", 0L)
        return if (ns > 0L) TestedLimit(ns, p.getBoolean("verified_confirmed", false)) else null
    }

    /** Keeps the larger limit; on a tie, keeps the brightness-confirmed one. Returns what is stored. */
    fun saveIfBetter(context: Context, candidate: TestedLimit): TestedLimit {
        val current = load(context)
        val best = if (current == null || candidate.maxNs > current.maxNs ||
            (candidate.maxNs == current.maxNs && candidate.brightnessConfirmed)
        ) candidate else current
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("verified_max_ns", best.maxNs)
            .putBoolean("verified_confirmed", best.brightnessConfirmed)
            .apply()
        return best
    }
}
