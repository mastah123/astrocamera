package com.astrocam.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/** Hardware capabilities of one camera, as reported by Camera2. */
data class CameraInfo(
    val id: String,
    val facing: String,
    val hardwareLevel: String,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val exposureRangeNs: ClosedRange<Long>?,
    val isoRange: ClosedRange<Int>?,
    val minFocusDiopters: Float,
    val manualSensor: Boolean,
    val manualFocus: Boolean,
    val manualPostProcessing: Boolean,
    val raw: Boolean,
    val aeCompRangeEv: ClosedRange<Float>?,
    val awbModeCount: Int,
    val formats: List<String>,
    val physicalIds: List<String>,
    val extensions: List<String> = emptyList(),
    val physicalWidthMm: Float? = null,
)

object CameraDetector {

    /** Reads every camera on the device. No camera permission is required. */
    fun detect(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        return ids.mapNotNull { id ->
            runCatching { read(id, manager.getCameraCharacteristics(id), detectExtensions(manager, id)) }.getOrNull()
        }
    }

    private fun read(id: String, c: CameraCharacteristics, extensions: List<String>): CameraInfo {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val pixel = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val aeRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val formats = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.outputFormats?.map(::formatName)?.distinct()?.sorted().orEmpty()

        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "Unknown"
        }

        return CameraInfo(
            id = id,
            facing = facing,
            hardwareLevel = level,
            sensorWidth = pixel?.width ?: 0,
            sensorHeight = pixel?.height ?: 0,
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList().orEmpty(),
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.toList().orEmpty(),
            exposureRangeNs = exposure?.let { it.lower..it.upper },
            isoRange = iso?.let { it.lower..it.upper },
            minFocusDiopters = minFocus,
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            manualFocus = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes,
            manualPostProcessing =
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps,
            raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            aeCompRangeEv = if (aeRange != null && aeStep > 0f &&
                (aeRange.lower != 0 || aeRange.upper != 0)
            ) (aeRange.lower * aeStep)..(aeRange.upper * aeStep) else null,
            awbModeCount = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.size ?: 0,
            formats = formats,
            physicalIds = c.physicalCameraIds.sorted(),
            extensions = extensions,
            physicalWidthMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width,
        )
    }

    /** Vendor extensions (for example the maker's Night mode). Needs Android 12 or newer. */
    private fun detectExtensions(manager: CameraManager, id: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return runCatching {
            manager.getCameraExtensionCharacteristics(id).supportedExtensions
                .map { extensionName(it) }.sorted()
        }.getOrDefault(emptyList())
    }

    private fun extensionName(type: Int): String = when (type) {
        0 -> "Auto"
        1 -> "Bokeh"
        2 -> "Face retouch"
        3 -> "HDR"
        4 -> "Night"
        else -> "Vendor $type"
    }

    private fun formatName(format: Int): String = when (format) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR (DNG)"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.PRIVATE -> "PRIVATE"
        0x48454946 -> "HEIC"
        else -> "0x%X".format(format)
    }
}
