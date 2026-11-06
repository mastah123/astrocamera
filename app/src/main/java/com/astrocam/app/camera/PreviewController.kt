package com.astrocam.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class ManualSettings(
    val manual: Boolean,
    val exposureNs: Long,
    val iso: Int,
    val focusDiopters: Float,
    val autoFocus: Boolean = false,
)

/** What the camera says it used for a frame. */
data class LiveReading(val exposureNs: Long?, val iso: Int?, val frameNs: Long?)

class CaptureOutcome(val jpeg: ByteArray, val reading: LiveReading)

data class PreviewConfig(
    val size: Size,
    val aspect: Float,
    val sensorOrientation: Int,
    val jpegSize: Size?,
)

/** Picks a preview size matching the sensor shape, the portrait aspect to show it at, and the photo size. */
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
    val jpeg = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
    return PreviewConfig(size, aspect, orientation, jpeg)
}

private fun CaptureRequest.Builder.applyControls(s: ManualSettings) {
    if (s.manual) {
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
        set(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
        set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(s.exposureNs, 33_333_333L))
        if (s.autoFocus) {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
        }
    } else {
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }
}

private fun TotalCaptureResult.toReading() = LiveReading(
    get(CaptureResult.SENSOR_EXPOSURE_TIME),
    get(CaptureResult.SENSOR_SENSITIVITY),
    get(CaptureResult.SENSOR_FRAME_DURATION),
)

/** Runs a live Camera2 preview, applies manual settings as they change, and takes photos. */
class PreviewController(
    private val context: Context,
    private val onReading: (LiveReading) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var latest: ManualSettings? = null
    private var lastReadingAt = 0L
    private var noiseMode: Int? = null
    private var edgeMode: Int? = null
    private var awbLockAvailable = false

    @Volatile
    private var pendingJpeg: CompletableDeferred<ByteArray>? = null

    val canCapture: Boolean get() = jpegReader != null && session != null

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult,
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReadingAt < 500) return
            lastReadingAt = now
            onReading(result.toReading())
        }
    }

    /** Returns an error message, or null when the preview is running. */
    @SuppressLint("MissingPermission")
    suspend fun start(
        cameraId: String,
        texture: SurfaceTexture,
        previewSize: Size,
        jpegSize: Size?,
        settings: ManualSettings,
    ): String? {
        stop()
        latest = latest ?: settings
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            val chars = manager.getCameraCharacteristics(cameraId)
            val nr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet().orEmpty()
            noiseMode = when {
                CameraMetadata.NOISE_REDUCTION_MODE_OFF in nr -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL in nr -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                else -> null
            }
            val edge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty()
            edgeMode = if (CameraMetadata.EDGE_MODE_OFF in edge) CameraMetadata.EDGE_MODE_OFF else null
            awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        }
        val t = HandlerThread("astro-preview").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        return try {
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)
            surface = previewSurface
            val dev = ExposureProbe.openCamera(manager, cameraId, h)
            device = dev

            val reader = jpegSize?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { r ->
                    r.setOnImageAvailableListener({ deliverJpeg(it) }, h)
                }
            }
            session = try {
                ExposureProbe.createSession(dev, listOfNotNull(previewSurface, reader?.surface), h)
                    .also { jpegReader = reader }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Some cameras cannot run a preview and a full-size photo stream together.
                runCatching { reader?.close() }
                ExposureProbe.createSession(dev, listOf(previewSurface), h)
            }
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

    private fun deliverJpeg(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            pendingJpeg?.complete(bytes)
        } finally {
            image.close()
        }
    }

    /** Takes one photo with the given settings. Returns null if it failed. Minimal mode reduces processing. */
    suspend fun capture(settings: ManualSettings, sensorOrientation: Int, minimal: Boolean = false): CaptureOutcome? {
        val dev = device ?: return null
        val sess = session ?: return null
        val reader = jpegReader ?: return null
        val h = handler ?: return null
        val deferred = CompletableDeferred<ByteArray>()
        pendingJpeg = deferred
        try {
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyControls(settings)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                if (minimal) {
                    // Less processing keeps frames consistent and easier to stack.
                    noiseMode?.let { set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                    edgeMode?.let { set(CaptureRequest.EDGE_MODE, it) }
                    if (awbLockAvailable) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
            }.build()
            val timeoutMs = settings.exposureNs / 1_000_000L + 10_000L
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<TotalCaptureResult?> { cont ->
                    sess.capture(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult,
                        ) {
                            if (cont.isActive) cont.resume(result)
                        }
                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }, h)
                }
            }
            val bytes = withTimeoutOrNull(5_000L) { deferred.await() }
            if (result == null || bytes == null || bytes.isEmpty()) return null
            return CaptureOutcome(bytes, result.toReading())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            pendingJpeg = null
        }
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
            builder.applyControls(s)
            sess.setRepeatingRequest(builder.build(), callback, h)
        } catch (e: Exception) {
            // The session may be closing; the next start() rebuilds it.
        }
    }

    fun stop() {
        pendingJpeg?.complete(ByteArray(0))
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { jpegReader?.close() }
        runCatching { surface?.release() }
        thread?.quitSafely()
        session = null
        device = null
        jpegReader = null
        surface = null
        thread = null
        handler = null
    }
}
