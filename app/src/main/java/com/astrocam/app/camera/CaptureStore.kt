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

class Saved(val name: String, val location: String)

/** Saves photos to the gallery and keeps a private log of the settings used for each one. */
object CaptureStore {

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun exposureTag(ns: Long): String = when {
        ns >= 1_000_000_000L -> "${ns / 1_000_000_000L}s"
        ns >= 1_000_000L -> "${ns / 1_000_000L}ms"
        else -> "${ns / 1_000L}us"
    }

    /** A folder name that groups the frames of one multi-frame capture. */
    fun newSession(): String = "SESSION_${stamp()}"

    /** Returns where the photo was saved, or null if saving failed. */
    fun save(
        context: Context,
        jpeg: ByteArray,
        settings: ManualSettings,
        reading: LiveReading,
        cameraId: String,
        session: String? = null,
        frame: Int = 0,
        total: Int = 1,
        thermal: ThermalReading? = null,
    ): Saved? {
        val time = session?.removePrefix("SESSION_") ?: stamp()
        val frameTag = if (session != null) "_F%03d".format(Locale.US, frame) else ""
        val tag = if (settings.manual) "${exposureTag(settings.exposureNs)}_ISO${settings.iso}" else "auto"
        val name = "ASTRO_$time${frameTag}_$tag.jpg"
        val folder = if (session != null) "AstroCamera/$session" else "AstroCamera"
        val location = runCatching { write(context, jpeg, name, "image/jpeg", folder) }.getOrNull()
            ?: return null
        runCatching { appendLog(context, name, location, settings, reading, cameraId, session, frame, total, thermal) }
        return Saved(name, location)
    }

    /** Saves a DNG from the RAW test. Returns the file name, or null if saving failed. */
    fun saveDng(context: Context, dng: ByteArray): String? {
        val name = "RAW_TEST_${stamp()}.dng"
        return runCatching { write(context, dng, name, "image/x-adobe-dng", "AstroCamera/RAW_TEST") }
            .getOrNull()?.let { name }
    }

    /** Saves a stacked image. */
    fun saveStacked(context: Context, jpeg: ByteArray, name: String, folder: String): Saved? {
        val location = runCatching { write(context, jpeg, name, "image/jpeg", folder) }.getOrNull() ?: return null
        return Saved(name, location)
    }

    private fun write(context: Context, bytes: ByteArray, name: String, mime: String, folder: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 needs a storage permission for the gallery, so use the app's own folder.
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), folder)
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            return file.absolutePath
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = resolver.openOutputStream(uri)?.use { it.write(bytes); true } ?: false
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
        session: String?,
        frame: Int,
        total: Int,
        thermal: ThermalReading?,
    ) {
        val line = JSONObject().apply {
            put("name", name)
            put("location", location)
            put("timeMillis", System.currentTimeMillis())
            put("cameraId", cameraId)
            put("appVersion", appVersion(context))
            put("session", session ?: JSONObject.NULL)
            put("frame", frame)
            put("totalFrames", total)
            put("manual", s.manual)
            put("requestedExposureNs", s.exposureNs)
            put("requestedIso", s.iso)
            put("focusDiopters", s.focusDiopters.toDouble())
            put("reportedExposureNs", r.exposureNs ?: JSONObject.NULL)
            put("reportedIso", r.iso ?: JSONObject.NULL)
            put("reportedFrameNs", r.frameNs ?: JSONObject.NULL)
            put("batteryTempC", thermal?.batteryC?.toDouble() ?: JSONObject.NULL)
            put("thermalStatus", thermal?.status ?: JSONObject.NULL)
        }
        File(context.filesDir, "captures.jsonl").appendText(line.toString() + "\n")
    }
}
