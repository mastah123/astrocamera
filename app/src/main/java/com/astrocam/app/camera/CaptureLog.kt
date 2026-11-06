package com.astrocam.app.camera

import android.content.Context
import android.net.Uri
import java.io.File
import org.json.JSONObject

data class FrameRef(val name: String, val location: String, val frame: Int)

/** One multi-frame capture, rebuilt from the private capture log. */
data class SessionInfo(
    val session: String,
    val frames: List<FrameRef>,
    val exposureNs: Long,
    val iso: Int,
    val manual: Boolean,
)

object CaptureLog {
    /** Multi-frame sessions with at least two frames, newest first. */
    fun sessions(context: Context): List<SessionInfo> {
        val file = File(context.filesDir, "captures.jsonl")
        if (!file.exists()) return emptyList()
        val grouped = linkedMapOf<String, MutableList<JSONObject>>()
        file.forEachLine { line ->
            runCatching {
                val o = JSONObject(line)
                if (!o.isNull("session")) grouped.getOrPut(o.getString("session")) { mutableListOf() }.add(o)
            }
        }
        return grouped.map { (session, entries) ->
            val first = entries.first()
            SessionInfo(
                session = session,
                frames = entries.sortedBy { it.optInt("frame") }
                    .map { FrameRef(it.getString("name"), it.getString("location"), it.optInt("frame")) },
                exposureNs = first.optLong("requestedExposureNs"),
                iso = first.optInt("requestedIso"),
                manual = first.optBoolean("manual"),
            )
        }.filter { it.frames.size >= 2 }.sortedByDescending { it.session }
    }

    /** Forgets frames that were deleted, so the Studio does not list them. */
    fun remove(context: Context, names: Set<String>) {
        val file = File(context.filesDir, "captures.jsonl")
        if (!file.exists() || names.isEmpty()) return
        val kept = file.readLines().filter { line ->
            runCatching { JSONObject(line).getString("name") !in names }.getOrDefault(true)
        }
        file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
    }
}

object Originals {
    /** Deletes the given files and returns how many were really removed. */
    fun delete(context: Context, refs: List<FrameRef>): Int {
        val removed = mutableSetOf<String>()
        refs.forEach { ref ->
            val ok = runCatching {
                if (ref.location.startsWith("content://")) {
                    context.contentResolver.delete(Uri.parse(ref.location), null, null) > 0
                } else {
                    File(ref.location).delete()
                }
            }.getOrDefault(false)
            if (ok) removed += ref.name
        }
        CaptureLog.remove(context, removed)
        return removed.size
    }
}
