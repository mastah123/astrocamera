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
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class RawResult(val ok: Boolean, val message: String)

/**
 * Tries a real RAW_SENSOR capture, checks that the pixels are not blank, and writes a DNG.
 * Some phones list RAW formats without really supporting them. Requires CAMERA permission.
 */
object RawProbe {

    @SuppressLint("MissingPermission")
    suspend fun run(context: Context, cameraId: String, onProgress: (String) -> Unit): RawResult {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return RawResult(false, "Could not read the camera's characteristics.")
        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width * it.height }
            ?: return RawResult(false, "This camera lists no RAW_SENSOR output size.")

        val thread = HandlerThread("raw-probe").also { it.start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var image: Image? = null
        try {
            val imageReady = CompletableDeferred<Image>()
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireNextImage()
                if (img != null && !imageReady.complete(img)) img.close()
            }, handler)
            reader = r

            onProgress("Camera $cameraId: opening")
            val dev = ExposureProbe.openCamera(manager, cameraId, handler)
            device = dev

            onProgress("Camera $cameraId: configuring a RAW stream")
            val sess = try {
                ExposureProbe.createSession(dev, listOf(r.surface), handler)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return RawResult(false, "The camera refused a RAW_SENSOR stream (${e.message ?: "configuration failed"}).")
            }
            session = sess

            onProgress("Camera $cameraId: capturing")
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()
            val result = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<TotalCaptureResult?> { cont ->
                    sess.capture(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult,
                        ) {
                            if (cont.isActive) cont.resume(res)
                        }
                        override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }, handler)
                }
            } ?: return RawResult(false, "The RAW capture failed or timed out.")

            val img = withTimeoutOrNull(5_000L) { imageReady.await() }
                ?: return RawResult(false, "The capture finished but no RAW image arrived.")
            image = img

            val stats = analyse(img) ?: return RawResult(false, "The RAW image had no readable pixel data.")
            val (min, max, _) = stats
            if (max <= min || max == 0) {
                return RawResult(false, "The RAW stream returned flat data (every sampled pixel was $min).")
            }

            onProgress("Camera $cameraId: writing DNG")
            val out = ByteArrayOutputStream()
            val dng = DngCreator(chars, result)
            try {
                dng.writeImage(out, img)
            } catch (e: Exception) {
                return RawResult(false, "Pixels look real, but DNG creation failed: ${e.message ?: e.javaClass.simpleName}.")
            } finally {
                dng.close()
            }
            val bytes = out.toByteArray()
            val saved = withContext(Dispatchers.IO) { CaptureStore.saveDng(context, bytes) }
            val mb = "%.1f".format(Locale.US, bytes.size / 1_048_576.0)
            return RawResult(
                true,
                "RAW works: ${size.width} × ${size.height}, pixel values $min to $max, DNG $mb MB" +
                    (saved?.let { ", saved as $it in Pictures/AstroCamera/RAW_TEST." } ?: ", but it could not be saved.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RawResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { image?.close() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
    }

    /** Smallest, largest and average of sparsely sampled 16-bit pixels. */
    private fun analyse(image: Image): Triple<Int, Int, Double>? {
        val plane = image.planes.firstOrNull() ?: return null
        val shorts = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        var min = Int.MAX_VALUE
        var max = 0
        var sum = 0L
        var count = 0
        var i = 0
        while (i < shorts.limit()) {
            val v = shorts.get(i).toInt() and 0xFFFF
            if (v < min) min = v
            if (v > max) max = v
            sum += v
            count++
            i += 997
        }
        return if (count == 0) null else Triple(min, max, sum.toDouble() / count)
    }
}
