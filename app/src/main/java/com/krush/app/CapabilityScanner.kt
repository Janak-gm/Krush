package com.krush.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object CapabilityScanner {

    fun scan(manager: CameraManager): String {
        val sb = StringBuilder()

        fun line(s: String = "") {
            sb.append(s).append('\n')
        }

        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            line("ERROR reading camera id list: ${e.message}")
            emptyArray()
        }

        line("===== KRUSH CAMERA CAPABILITY REPORT =====")
        line("Camera count: ${ids.size}")
        line()

        for (id in ids) {
            val ch = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                line("ERROR reading characteristics for $id: ${e.message}")
                continue
            }
            scanCamera(id, ch, sb)
            line()
        }

        return sb.toString()
    }

    private fun scanCamera(id: String, ch: CameraCharacteristics, sb: StringBuilder) {
        fun line(s: String = "") {
            sb.append(s).append('\n')
        }

        line("----------------------------------------")
        line("CAMERA ID: $id")
        line("----------------------------------------")

        // Lens facing
        val facing = ch.get(CameraCharacteristics.LENS_FACING)
        line("Lens facing: ${describeFacing(facing)}")

        // Orientation
        val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION)
        line("Sensor orientation: $orientation deg")

        // Resolution info
        val activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        line("Active array (sensor readout): ${activeArray?.width()}x${activeArray?.height()}")

        val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        line("Pixel array (full sensor): ${pixelArray?.width}x${pixelArray?.height}")

        // Physical sensor
        val physicalSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (physicalSize != null) {
            line("Physical sensor size: %.3f x %.3f mm".format(physicalSize.width, physicalSize.height))
        } else {
            line("Physical sensor size: UNKNOWN")
        }

        val focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        line("Focal lengths: ${focalLengths?.joinToString(", ") ?: "UNKNOWN"}")

        val apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        line("Apertures: ${apertures?.joinToString(", ") ?: "UNKNOWN"}")

        // Formats / resolutions
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            line()
            line("--- SUPPORTED OUTPUT SIZES ---")
            line("JPEG: ${map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.joinToString(", ") { "${it.width}x${it.height}" } ?: "NONE"}")
            line("YUV_420_888: ${map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.joinToString(", ") { "${it.width}x${it.height}" } ?: "NONE"}")
            line("RAW_SENSOR (DNG): ${map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.joinToString(", ") { "${it.width}x${it.height}" } ?: "NONE"}")
            line("RAW10: ${map.getOutputSizes(android.graphics.ImageFormat.RAW10)?.joinToString(", ") { "${it.width}x${it.height}" } ?: "NONE"}")
            line("PRIVATE: ${map.getOutputSizes(android.graphics.ImageFormat.PRIVATE)?.joinToString(", ") { "${it.width}x${it.height}" } ?: "NONE"}")

            // High-speed video
            val highSpeed = map.highSpeedVideoSizes
            line("High-speed video sizes: ${if (highSpeed.isNullOrEmpty()) "NONE" else highSpeed.joinToString(", ") { "${it.width}x${it.height}" }}")

            // Input formats (reprocess)
            line("Input formats: ${map.inputFormats?.joinToString(", ") { formatName(it) } ?: "NONE"}")
            line("Output formats: ${map.outputFormats?.joinToString(", ") { formatName(it) } ?: "NONE"}")
        } else {
            line("Stream configuration map: NULL")
        }

        // FPS ranges
        line()
        line("--- AE FPS RANGES ---")
        val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        line(fpsRanges?.joinToString(", ") { "${it.lower}..${it.upper}" } ?: "NONE")

        // Exposure / ISO / shutter
        line()
        line("--- EXPOSURE / ISO / SHUTTER ---")
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        line("ISO range: ${isoRange?.lower}..${isoRange?.upper}")

        val exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (exposureRange != null) {
            line("Shutter speed range: %.6f s .. %.4f s".format(exposureRange.lower / 1e9, exposureRange.upper / 1e9))
        } else {
            line("Shutter speed range: NONE")
        }

        val aeCompRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeCompStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        line("Exposure compensation range: ${aeCompRange?.lower}..${aeCompRange?.upper} (step ${aeCompStep?.toFloat()})")

        val maxAfRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
        line("Max AF regions: $maxAfRegions")

        // Modes
        line()
        line("--- MODES ---")
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        line("Autofocus modes: ${afModes?.joinToString(", ") { afName(it) } ?: "NONE"}")

        val awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        line("White balance modes: ${awbModes?.joinToString(", ") { awbName(it) } ?: "NONE"}")

        val aeModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        line("AE modes: ${aeModes?.joinToString(", ") { aeName(it) } ?: "NONE"}")

        val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
        line("Flash available: ${flash ?: false}")

        // Capabilities
        line()
        line("--- CAPABILITIES ---")
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        line("Camera2 capabilities:")
        caps?.forEach { line("  - ${capName(it)}") }

        val rawAvailable = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        line("RAW available: ${if (rawAvailable) "YES" else "NO"}")

        val oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        line("OIS modes: ${oisModes?.joinToString(", ") { oisName(it) } ?: "NONE"}")

        val videoStabModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        line("Video stabilization (EIS) modes: ${videoStabModes?.joinToString(", ") { videoStabName(it) } ?: "NONE"}")

        val hwLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        line("Hardware level: ${hwLevelName(hwLevel)}")

        // AE lock, etc.
        line()
        line("--- OTHER ---")
        val aeLock = ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)
        line("AE lock available: ${aeLock ?: false}")

        val awbLock = ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)
        line("AWB lock available: ${awbLock ?: false}")

        val maxFaceCount = ch.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT)
        line("Max face detection count: $maxFaceCount")

        val lensFacing = ch.get(CameraCharacteristics.LENS_FACING)
        val zoomRatio = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        line("Max digital zoom: $zoomRatio")
    }

    fun deviceInfo(): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }
        line("===== DEVICE INFO =====")
        line("Model: ${android.os.Build.MODEL}")
        line("Manufacturer: ${android.os.Build.MANUFACTURER}")
        line("Device: ${android.os.Build.DEVICE}")
        line("Brand: ${android.os.Build.BRAND}")
        line("Product: ${android.os.Build.PRODUCT}")
        line("Android version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        line("Supported ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
        line("Fingerprint: ${android.os.Build.FINGERPRINT}")
        line()
        return sb.toString()
    }

    fun memoryInfo(activityManager: android.app.ActivityManager): String {
        val mi = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mi)
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }
        line("===== MEMORY =====")
        line("Total RAM: %.2f GB".format(mi.totalMem / 1024.0 / 1024.0 / 1024.0))
        line("Available RAM: %.2f GB".format(mi.availMem / 1024.0 / 1024.0 / 1024.0))
        line("Low memory: ${mi.lowMemory}")
        line()
        return sb.toString()
    }

    // --- helpers ---
    private fun describeFacing(f: Int?): String = when (f) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN ($f)"
    }

    private fun formatName(f: Int): String = when (f) {
        android.graphics.ImageFormat.JPEG -> "JPEG"
        android.graphics.ImageFormat.YUV_420_888 -> "YUV_420_888"
        android.graphics.ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        android.graphics.ImageFormat.RAW10 -> "RAW10"
        android.graphics.ImageFormat.RAW12 -> "RAW12"
        android.graphics.ImageFormat.PRIVATE -> "PRIVATE"
        android.graphics.ImageFormat.DEPTH16 -> "DEPTH16"
        android.graphics.ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
        android.graphics.ImageFormat.HEIC -> "HEIC"
        else -> "UNKNOWN($f)"
    }

    private fun afName(v: Int): String = when (v) {
        CameraCharacteristics.CONTROL_AF_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraCharacteristics.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONT_VIDEO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONT_PICTURE"
        CameraCharacteristics.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "UNKNOWN($v)"
    }

    private fun awbName(v: Int): String = when (v) {
        CameraCharacteristics.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraCharacteristics.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "UNKNOWN($v)"
    }

    private fun aeName(v: Int): String = when (v) {
        CameraCharacteristics.CONTROL_AE_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_AE_MODE_ON -> "ON"
        CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
        CameraCharacteristics.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
        CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
        else -> "UNKNOWN($v)"
    }

    private fun capName(v: Int): String = when (v) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        else -> "UNKNOWN($v)"
    }

    private fun oisName(v: Int): String = when (v) {
        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
        else -> "UNKNOWN($v)"
    }

    private fun videoStabName(v: Int): String = when (v) {
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"
        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "PREVIEW_STABILIZATION"
        else -> "UNKNOWN($v)"
    }

    private fun hwLevelName(v: Int?): String = when (v) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN ($v)"
    }
}
