package com.astrocam.app.camera

import android.content.Context
import java.io.File
import java.util.Date

/** Keeps the most recent crash or handled error so it can be shared from Settings. */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, error, "Crash on thread ${thread.name}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /** For errors the app caught and survived. */
    fun record(context: Context, error: Throwable) {
        runCatching { write(context, error, "Handled error") }
    }

    private fun write(context: Context, error: Throwable, kind: String) {
        val text = buildString {
            appendLine("Astro Camera ${appVersion(context)} (build ${appBuild(context)})")
            appendLine("Device: ${deviceName()}")
            appendLine("Time: ${Date()}")
            appendLine("Kind: $kind")
            appendLine()
            append(error.stackTraceToString())
        }
        File(context.filesDir, FILE).writeText(text)
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
