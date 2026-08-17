package com.krush.app

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraController(
    private val context: Context,
    private val textureView: TextureView
) {
    companion object {
        private const val TAG = "KrushCamera"
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

    var flashOn = false
    var manualExposure = false
    var iso = 100
    var shutterNs = 33_000_000L
    var ev = 0

    var cameraIds: List<String> = emptyList()
        private set

    private var capturing = false

    fun init(onReady: (Boolean) -> Unit) {
        cameraIds = cameraManager.cameraIdList.toList()
        cameraId = rearCameraId() ?: cameraIds.firstOrNull().orEmpty()
        openCamera(onReady)
    }

    fun switchCamera(onSwitched: (Boolean) -> Unit) {
        if (cameraIds.size < 2) {
            onSwitched(false)
            return
        }
        val next = cameraIds[(cameraIds.indexOf(cameraId) + 1) % cameraIds.size]
        cameraId = next
        closeCamera()
        openCamera(onSwitched)
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

    private fun openCamera(onReady: (Boolean) -> Unit) {
        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            jpegSize = map?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

            jpegSize?.let { s ->
                imageReader = ImageReader.newInstance(s.width, s.height, ImageFormat.JPEG, 2)
            }

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
                    onReady(false)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
            onReady(false)
        }
    }

    private fun startPreview(onReady: (Boolean) -> Unit) {
        try {
            val device = cameraDevice ?: throw IllegalStateException("no device")
            val texture = textureView.surfaceTexture
                ?: throw IllegalStateException("no surface texture")

            val size = jpegSize ?: Size(1920, 1080)
            texture.setDefaultBufferSize(size.width, size.height)
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
                    onReady(true)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onReady(false)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed", e)
            onReady(false)
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

        b.set(
            CaptureRequest.FLASH_MODE,
            if (flashOn) CaptureRequest.FLASH_MODE_SINGLE else CaptureRequest.FLASH_MODE_OFF
        )

        return b
    }

    private fun saveJpeg(bytes: ByteArray): Boolean {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Krush_$ts.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/Krush"
                )
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
