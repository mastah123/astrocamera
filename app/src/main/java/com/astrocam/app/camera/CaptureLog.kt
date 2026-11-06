package com.astrocam.app.camera

import android.content.Context
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
}
