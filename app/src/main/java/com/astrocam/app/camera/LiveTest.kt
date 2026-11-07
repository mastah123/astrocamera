package com.astrocam.app.camera

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class LiveTestResult(val verdict: String, val summary: String)

/**
 * Takes a short burst twice, once only capturing and once while stacking live, and compares
 * speed, waiting, memory and heat to decide whether this phone can stack while it shoots.
 */
object LiveTest {

    suspend fun run(
        context: Context,
        controller: PreviewController,
        ms: ManualSettings,
        orientation: Int,
        frames: Int,
        onProgress: (String) -> Unit,
    ): LiveTestResult = coroutineScope {
        val runtime = Runtime.getRuntime()
        var peakMemoryMb = 0.0
        val startTemp = Thermal.read(context).batteryC
        var peakTemp = startTemp
        var maxStatus = 0

        fun sample() {
            val used = (runtime.totalMemory() - runtime.freeMemory() + Debug.getNativeHeapAllocatedSize()) / 1_048_576.0
            if (used > peakMemoryMb) peakMemoryMb = used
            val t = Thermal.read(context)
            t.batteryC?.let { if (peakTemp == null || it > peakTemp!!) peakTemp = it }
            maxStatus = max(maxStatus, t.status ?: 0)
        }

        // Round 1: capture only.
        val startA = SystemClock.elapsedRealtime()
        var capturedA = 0
        for (i in 1..frames) {
            onProgress("Round 1 of 2, capture only: $i of $frames")
            if (controller.capture(ms, orientation, true) != null) capturedA++
            sample()
        }
        val timeA = SystemClock.elapsedRealtime() - startA

        // Round 2: capture while every frame is aligned and added in the background.
        val live = Stacker.Live(StackMode.Average)
        val channel = Channel<ByteArray>(2)
        val processed = AtomicInteger(0)
        val consumer = launch(Dispatchers.Default) {
            for (bytes in channel) {
                live.add(bytes)
                processed.incrementAndGet()
            }
        }
        var sent = 0
        var waits = 0
        var maxQueue = 0
        var capturedB = 0
        val startB = SystemClock.elapsedRealtime()
        for (i in 1..frames) {
            onProgress("Round 2 of 2, capture with live stacking: $i of $frames")
            val outcome = controller.capture(ms, orientation, true)
            if (outcome != null) {
                capturedB++
                val before = SystemClock.elapsedRealtime()
                channel.send(outcome.jpeg)
                if (SystemClock.elapsedRealtime() - before > 20) waits++
                sent++
                maxQueue = max(maxQueue, sent - processed.get())
            }
            sample()
        }
        channel.close()
        onProgress("Finishing the live stack…")
        consumer.join()
        val timeB = SystemClock.elapsedRealtime() - startB
        sample()

        val finishStart = SystemClock.elapsedRealtime()
        live.finish(context, "TEST", false, "", false, emptyList(), save = false)
        val finishMs = SystemClock.elapsedRealtime() - finishStart
        sample()

        val perA = timeA / max(1, capturedA).toDouble()
        val perB = timeB / max(1, capturedB).toDouble()
        val slowdown = (perB - perA) / perA
        val maxMemoryMb = runtime.maxMemory() / 1_048_576.0
        val memoryFraction = peakMemoryMb / maxMemoryMb

        val verdict = when {
            capturedA < frames / 2 || capturedB < frames / 2 -> "Test failed"
            slowdown <= 0.20 && waits <= frames * 0.10 && memoryFraction < 0.75 && maxStatus < 2 -> "Handles live stacking"
            memoryFraction < 0.90 && maxStatus < 3 -> "Use batches"
            else -> "Not recommended"
        }
        val advice = when (verdict) {
            "Handles live stacking" -> "Live stacking should keep up with this phone. You can turn it on in Settings, Experimental."
            "Use batches" -> "Live stacking slowed capture or used a lot of memory. Stack in batches instead, for example every 10 frames."
            "Not recommended" -> "This phone struggled. Stack after the burst, and keep temperature protection on."
            else -> "Too few frames were captured. Check the camera and try again."
        }
        val summary = buildString {
            appendLine("Capture only: ${"%.0f".format(Locale.US, perA)} ms per frame")
            appendLine("With live stacking: ${"%.0f".format(Locale.US, perB)} ms per frame (${"%+.0f".format(Locale.US, slowdown * 100)}%)")
            appendLine("Capture had to wait for stacking: $waits of $capturedB frames")
            appendLine("Longest queue of waiting frames: $maxQueue")
            appendLine("Peak memory: ${"%.0f".format(Locale.US, peakMemoryMb)} MB of ${"%.0f".format(Locale.US, maxMemoryMb)} MB")
            appendLine("Temperature: ${startTemp?.let { "%.1f".format(Locale.US, it) } ?: "n/a"} to ${peakTemp?.let { "%.1f".format(Locale.US, it) } ?: "n/a"} °C, thermal status ${Thermal.statusName(maxStatus)}")
            appendLine("Finishing took ${finishMs} ms")
            appendLine(if (live.aligned) "Stars were found and used for alignment." else "No stars were found, so frames were not aligned. Real use with stars is heavier than this test.")
            append(advice)
        }
        LiveTestResult(verdict, summary)
    }
}
