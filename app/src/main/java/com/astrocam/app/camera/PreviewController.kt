package com.astrocam.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

data class ManualSettings(
    val manual: Boolean,
    val exposureNs: Long,
    val iso: Int,
    val focusDiopters: Float,
)

/** What the camera says it used for the latest preview frame. */
data class LiveReading(val exposureNs: Long?, val iso: Int?, val frameNs: Long?)

data class PreviewConfig(val size: Size, val aspect: Float)

/** Picks a preview size matching the sensor shape, and the portrait aspect ratio to show it at. */
fun previewConfig(context: Context, cameraId: String): PreviewConfig? {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val c = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val sensorAspect = sensor?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
    val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        .filter { it.width <= 1920 }
    val matching = sizes.filter { abs(it.width.toFloat() / it.height - sensorAspect) < 0.02f }
    val size = matching.ifEmpty { sizes }.maxByOrNull { it.width * it.height } ?: return null
    val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val aspect = if (orientation % 180 == 90) size.height.toFloat() / size.width
    else size.width.toFloat() / size.height
    return PreviewConfig(size, aspect)
}

/** Runs a live Camera2 preview and applies manual settings as they change. */
class PreviewController(
    private val context: Context,
    private val onReading: (LiveReading) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var latest: ManualSettings? = null
    private var lastReadingAt = 0L

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult,
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadingAt < 500) return
            lastReadingAt = now
            onReading(
                LiveReading(
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    result.get(CaptureResult.SENSOR_SENSITIVITY),
                    result.get(CaptureResult.SENSOR_FRAME_DURATION),
                )
            )
        }
    }

    /** Returns an error message, or null when the preview is running. */
    @SuppressLint("MissingPermission")
    suspend fun start(cameraId: String, texture: SurfaceTexture, size: Size, settings: ManualSettings): String? {
        stop()
        latest = latest ?: settings
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val t = HandlerThread("astro-preview").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        return try {
            texture.setDefaultBufferSize(size.width, size.height)
            val s = Surface(texture)
            surface = s
            val dev = ExposureProbe.openCamera(manager, cameraId, h)
            device = dev
            session = ExposureProbe.createSession(dev, s, h)
            apply()
            null
        } catch (e: CancellationException) {
            stop()
            throw e
        } catch (e: Exception) {
            stop()
            e.message ?: e.javaClass.simpleName
        }
    }

    fun update(settings: ManualSettings) {
        latest = settings
        apply()
    }

    private fun apply() {
        val s = latest ?: return
        val dev = device ?: return
        val sess = session ?: return
        val surf = surface ?: return
        val h = handler ?: return
        try {
            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surf)
            if (s.manual) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(s.exposureNs, 33_333_333L))
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            sess.setRepeatingRequest(builder.build(), callback, h)
        } catch (e: Exception) {
            // The session may be closing; the next start() rebuilds it.
        }
    }

    fun stop() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { surface?.release() }
        thread?.quitSafely()
        session = null
        device = null
        surface = null
        thread = null
        handler = null
    }
}
