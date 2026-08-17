package com.krush.app

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraController(
    private val context: Context,
    private val textureView: AutoFitTextureView
) {
    companion object {
        private const val TAG = "KrushCamera"

        private val ORIENTATIONS = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }

        enum class Aspect(val w: Int, val h: Int, val label: String) {
            FOUR_THREE(4, 3, "4:3"),
            SIXTEEN_NINE(16, 9, "16:9"),
            NINE_SIXTEEN(9, 16, "9:16"),
            ONE_ONE(1, 1, "1:1");

            override fun toString(): String = label
        }
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val backgroundThread = HandlerThread("KrushCameraThread").apply { start() }
    private val handler = Handler(backgroundThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var cameraId: String = ""
    private var characteristics: CameraCharacteristics? = null
    private var jpegSize: Size? = null
    private var previewSize: Size? = null

    var flashOn = false
    var manualExposure = false
    var iso = 100
    var shutterNs = 33_000_000L
    var ev = 0

    var cameraIds: List<String> = emptyList()
        private set

    var currentAspect = Aspect.FOUR_THREE
        private set

    private var capturing = false
    private var mediaRecorder: MediaRecorder? = null
    private var recording = false
    private var recordingFile: File? = null
    private var sensorOrientation = 90

    var onRecordingStateChanged: ((Boolean) -> Unit)? = null

    fun init(onReady: (String?) -> Unit) {
        cameraIds = cameraManager.cameraIdList.toList()
        cameraId = rearCameraId() ?: cameraIds.firstOrNull().orEmpty()
        openCamera(onReady)
    }

    fun setAspect(aspect: Aspect) {
        if (currentAspect == aspect) return
        currentAspect = aspect
        handler.post {
            // Rebuild sizes and session for the new aspect.
            closeCamera()
            openCamera { err -> if (err != null) Log.e(TAG, "reconfig failed: $err") }
        }
    }

    fun switchCamera(onSwitched: (Boolean) -> Unit) {
        if (cameraIds.size < 2) {
            onSwitched(false)
            return
        }
        val next = cameraIds[(cameraIds.indexOf(cameraId) + 1) % cameraIds.size]
        cameraId = next
        closeCamera()
        openCamera { err -> onSwitched(err == null) }
    }

    fun capture(onSaved: (Boolean) -> Unit) {
        if (capturing) {
            onSaved(false)
            return
        }
        capturing = true
        handler.post {
            try {
                val device = cameraDevice ?: throw IllegalStateException("no device")
                val session = captureSession ?: throw IllegalStateException("no session")
                val reader = imageReader ?: throw IllegalStateException("no reader")

                val req = buildCaptureRequest(device, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    previewSurface?.let { addTarget(it) }
                }.build()

                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    val ok = saveJpeg(bytes)
                    capturing = false
                    onSaved(ok)
                }, handler)

                session.capture(req, null, handler)
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                capturing = false
                onSaved(false)
            }
        }
    }

    fun isRecording(): Boolean = recording

    fun startRecording(onResult: (Boolean) -> Unit) {
        if (recording) {
            onResult(false)
            return
        }
        handler.post {
            try {
                val device = cameraDevice ?: throw IllegalStateException("no device")
                if (!prepareRecorder()) {
                    onResult(false)
                    return@post
                }
                val recorder = mediaRecorder ?: throw IllegalStateException("no recorder")

                val texture = textureView.surfaceTexture
                    ?: throw IllegalStateException("no surface texture")
                val preview = Surface(texture)
                previewSurface = preview

                val surfaces = mutableListOf<Surface>(preview, recorder.surface)
                imageReader?.let { surfaces.add(it.surface) }

                device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val req = buildCaptureRequest(device, CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(recorder.surface)
                                addTarget(preview)
                            }.build()
                            recorder.start()
                            recording = true
                            runOnUiThread { onRecordingStateChanged?.invoke(true) }
                            session.setRepeatingRequest(req, null, handler)
                            onResult(true)
                        } catch (e: Exception) {
                            Log.e(TAG, "record start failed", e)
                            onResult(false)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "record session configure failed")
                        onResult(false)
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                onResult(false)
            }
        }
    }

    fun stopRecording(onResult: (String?) -> Unit) {
        if (!recording) {
            onResult(null)
            return
        }
        handler.post {
            val file = recordingFile
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "stop failed", e)
            }
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            recording = false
            runOnUiThread { onRecordingStateChanged?.invoke(false) }

            // Recreate a preview-only session (recording session is now closed with the device).
            try {
                closeCamera()
                openCamera { err -> if (err != null) Log.e(TAG, "restore preview failed: $err") }
            } catch (e: Exception) {
                Log.e(TAG, "restore preview failed", e)
            }

            val saved = file?.let { saveVideo(it) }
            onResult(saved)
        }
    }

    fun applySettings() {
        handler.post {
            try {
                val session = captureSession ?: return@post
                val device = cameraDevice ?: return@post
                val req = buildCaptureRequest(device, CameraDevice.TEMPLATE_PREVIEW).apply {
                    previewSurface?.let { addTarget(it) }
                }.build()
                session.setRepeatingRequest(req, null, handler)
            } catch (e: Exception) {
                Log.e(TAG, "applySettings failed", e)
            }
        }
    }

    fun close() {
        closeCamera()
        backgroundThread.quitSafely()
    }

    fun currentPhotoSize(): String = jpegSize?.let { "${it.width}x${it.height}" } ?: "?"

    fun currentVideoSize(): String = videoSize()?.let { "${it.width}x${it.height}" } ?: "1080x1920"

    private fun runOnUiThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun openCamera(onReady: (String?) -> Unit) {
        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            jpegSize = chooseSize(
                map?.getOutputSizes(ImageFormat.JPEG)?.toList(),
                currentAspect
            )
            previewSize = chooseSize(
                map?.getOutputSizes(ImageFormat.PRIVATE)?.toList(),
                currentAspect
            )

            val imgSize = jpegSize ?: Size(4064, 3048)
            imageReader = ImageReader.newInstance(imgSize.width, imgSize.height, ImageFormat.JPEG, 2)

            val isFront = characteristics?.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            val pSize = previewSize ?: Size(1920, 1080)
            textureView.setCameraParams(pSize.width, pSize.height, sensorOrientation, isFront)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview(onReady)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    onReady("camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
            onReady(e.message)
        }
    }

    private fun startPreview(onReady: (String?) -> Unit) {
        try {
            val device = cameraDevice ?: throw IllegalStateException("no device")
            val texture = textureView.surfaceTexture
                ?: throw IllegalStateException("no surface texture")

            val pSize = previewSize ?: Size(1920, 1080)
            texture.setDefaultBufferSize(pSize.width, pSize.height)
            val surface = Surface(texture)
            previewSurface = surface

            val surfaces = mutableListOf<Surface>(surface)
            imageReader?.let { surfaces.add(it.surface) }

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val req = buildCaptureRequest(device, CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                    }.build()
                    session.setRepeatingRequest(req, null, handler)
                    onReady(null)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onReady("configure failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed", e)
            onReady(e.message)
        }
    }

    private fun buildCaptureRequest(device: CameraDevice, template: Int): CaptureRequest.Builder {
        val b = device.createCaptureRequest(template)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        if (manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
        }

        val isVideo = template == CameraDevice.TEMPLATE_RECORD
        b.set(
            CaptureRequest.FLASH_MODE,
            if (flashOn && !isVideo) CaptureRequest.FLASH_MODE_SINGLE else CaptureRequest.FLASH_MODE_OFF
        )

        return b
    }

    private fun prepareRecorder(): Boolean {
        return try {
            val videoSize = videoSize() ?: Size(1920, 1080)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(
                context.getExternalFilesDir(null),
                "krush_video_$ts.mp4"
            )
            recordingFile = file

            val recorder = if (android.os.Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.absolutePath)
            recorder.setVideoEncodingBitRate(12_000_000)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(videoSize.width, videoSize.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOrientationHint(sensorOrientation)
            recorder.prepare()
            mediaRecorder = recorder
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareRecorder failed", e)
            false
        }
    }

    private fun videoSize(): Size? {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: return null
        // Prefer 1920x1080 (or the largest <=1080p) for stable video.
        val landscape1080 = sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        return landscape1080 ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun saveVideo(file: File): String? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Krush")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return file.absolutePath
            resolver.openOutputStream(uri).use { out ->
                out?.write(file.readBytes())
            }
            file.delete()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveVideo failed", e)
            file.absolutePath
        }
    }

    private fun saveJpeg(bytes: ByteArray): Boolean {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Krush_$ts.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Krush")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri).use { it?.write(bytes) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveJpeg failed", e)
            false
        }
    }

    private fun chooseSize(sizes: List<Size>?, aspect: Aspect): Size? {
        if (sizes.isNullOrEmpty()) return null
        // The sensor always outputs landscape buffers, so map 9:16 -> 16:9 for
        // buffer selection (the texture rotation turns it into portrait display).
        val effectiveW = if (aspect.w < aspect.h) aspect.h else aspect.w
        val effectiveH = if (aspect.w < aspect.h) aspect.w else aspect.h
        val targetRatio = effectiveW.toDouble() / effectiveH
        val best = sizes.filter { s ->
            val ratio = s.width.toDouble() / s.height
            Math.abs(ratio - targetRatio) < 0.03
        }.maxByOrNull { it.width.toLong() * it.height.toLong() }
        return best ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun rearCameraId(): String? {
        for (id in cameraIds) {
            val ch = cameraManager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return null
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface = null
    }
}
