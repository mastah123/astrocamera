package com.astrocam.app.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** Saves photos to the gallery and keeps a private log of the settings used for each one. */
object CaptureStore {

    fun exposureTag(ns: Long): String = when {
        ns >= 1_000_000_000L -> "${ns / 1_000_000_000L}s"
        ns >= 1_000_000L -> "${ns / 1_000_000L}ms"
        else -> "${ns / 1_000L}us"
    }

    /** Returns the saved file name, or null if saving failed. */
    fun save(
        context: Context,
        jpeg: ByteArray,
        settings: ManualSettings,
        reading: LiveReading,
        cameraId: String,
    ): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tag = if (settings.manual) "${exposureTag(settings.exposureNs)}_ISO${settings.iso}" else "auto"
        val name = "ASTRO_${stamp}_$tag.jpg"
        val location = runCatching { write(context, jpeg, name) }.getOrNull() ?: return null
        runCatching { appendLog(context, name, location, settings, reading, cameraId) }
        return name
    }

    private fun write(context: Context, jpeg: ByteArray, name: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 needs a storage permission for the gallery, so use the app's own folder.
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AstroCamera")
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(jpeg)
            return file.absolutePath
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AstroCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = resolver.openOutputStream(uri)?.use { it.write(jpeg); true } ?: false
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    private fun appendLog(
        context: Context,
        name: String,
        location: String,
        s: ManualSettings,
        r: LiveReading,
        cameraId: String,
    ) {
        val line = JSONObject().apply {
            put("name", name)
            put("location", location)
            put("timeMillis", System.currentTimeMillis())
            put("cameraId", cameraId)
            put("appVersion", appVersion(context))
            put("manual", s.manual)
            put("requestedExposureNs", s.exposureNs)
            put("requestedIso", s.iso)
            put("focusDiopters", s.focusDiopters.toDouble())
            put("reportedExposureNs", r.exposureNs ?: JSONObject.NULL)
            put("reportedIso", r.iso ?: JSONObject.NULL)
            put("reportedFrameNs", r.frameNs ?: JSONObject.NULL)
        }
        File(context.filesDir, "captures.jsonl").appendText(line.toString() + "\n")
    }
}
