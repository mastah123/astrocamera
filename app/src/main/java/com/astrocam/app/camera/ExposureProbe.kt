package com.astrocam.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends real manual-exposure requests and reads back what the camera actually
 * used. Phones that do not advertise manual control sometimes honor it anyway.
 * Requires the CAMERA permission.
 */
object ExposureProbe {

    // Requested exposures. Two or more matching results proves manual control.
    private val LADDER_NS = longArrayOf(
        10_000_000L, 100_000_000L, 500_000_000L,
        1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L,
    )
    private const val TEST_ISO = 800

    @SuppressLint("MissingPermission")
    suspend fun run(context: Context, info: CameraInfo, onProgress: (String) -> Unit): ProbeResult {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("exposure-probe").also { it.start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        try {
            val chars = manager.getCameraCharacteristics(info.id)
            val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { it.width * it.height }
                ?: return failed(info.id, "No YUV output size available.")

            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            imageReader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            reader = imageReader

            onProgress("Camera ${info.id}: opening")
            val cam = openCamera(manager, info.id, handler)
            device = cam
            val sess = createSession(cam, imageReader.surface, handler)
            session = sess

            val iso = TEST_ISO.coerceIn(info.isoRange?.start ?: TEST_ISO, info.isoRange?.endInclusive ?: TEST_ISO)
            val steps = mutableListOf<ExposureStep>()
            var isoApplied: Int? = null

            for (ns in LADDER_NS) {
                onProgress("Camera ${info.id}: requesting ${formatExposure(ns)}")
                val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, ns + 50_000_000L)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                }.build()
                val result = captureOnce(sess, request, handler, ns / 1_000_000L + 6_000L)
                val applied = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                isoApplied = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: isoApplied
                val honored = applied != null && abs(applied - ns) <= ns * 0.15
                steps += ExposureStep(ns, applied, honored)
                if (!honored) break
            }

            onProgress("Camera ${info.id}: testing auto night boost")
            val boost = runCatching { autoBoost(cam, sess, imageReader.surface, chars, handler) }.getOrNull()

            return ProbeResult(info.id, steps, iso, isoApplied, boost)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(info.id, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
    }

    private fun failed(id: String, message: String) =
        ProbeResult(id, emptyList(), null, null, null, message)

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, id: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                }
            }, handler)
        }

    private suspend fun createSession(device: CameraDevice, surface: Surface, handler: Handler): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface)),
                Executor { task -> handler.post(task) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Session configuration failed"))
                    }
                },
            )
            device.createCaptureSession(config)
        }

    private suspend fun captureOnce(
        session: CameraCaptureSession,
        request: CaptureRequest,
        handler: Handler,
        timeoutMs: Long,
    ): TotalCaptureResult? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<TotalCaptureResult?> { cont ->
            try {
                session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult,
                    ) {
                        if (cont.isActive) cont.resume(result)
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                        if (cont.isActive) cont.resume(null)
                    }
                }, handler)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** Auto exposure with max compensation and the lowest frame rate, like a basic night mode. */
    private suspend fun autoBoost(
        device: CameraDevice,
        session: CameraCaptureSession,
        surface: Surface,
        chars: CameraCharacteristics,
        handler: Handler,
    ): Long? {
        val slowest: Range<Int>? = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.minWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
        val maxComp = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, maxComp)
            if (slowest != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slowest)
        }.build()

        val exposure = withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine<Long?> { cont ->
                var frames = 0
                session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult,
                    ) {
                        frames++
                        if (frames >= 20 && cont.isActive) {
                            cont.resume(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                        }
                    }
                }, handler)
            }
        }
        runCatching { session.stopRepeating() }
        return exposure
    }
}
