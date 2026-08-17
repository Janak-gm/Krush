package com.krush.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CameraLab {

    private const val TAG = "KrushCameraLab"

    class Result(
        val yuvFile: File?,
        val jpegFile: File?,
        val yuvThumbFile: File?,
        val jpegThumbFile: File?,
        val log: String
    )

    fun rearCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }

    fun run(context: Context, outputDir: File): Result {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = rearCameraId(manager) ?: return Result(null, null, null, null, "No camera found\n")
        val ch = manager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Result(null, null, null, null, "No stream config map\n")

        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
        val maxYuv = yuvSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        val maxJpeg = jpegSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }

        line("Rear camera: $id")
        line("Max YUV: ${maxYuv?.width}x${maxYuv?.height}")
        line("Max JPEG: ${maxJpeg?.width}x${maxJpeg?.height}")

        if (maxYuv == null || maxJpeg == null) {
            return Result(null, null, null, null, sb.toString() + "Missing YUV or JPEG sizes\n")
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val yuvReader = ImageReader.newInstance(maxYuv.width, maxYuv.height, ImageFormat.YUV_420_888, 2)
        val jpegReader = ImageReader.newInstance(maxJpeg.width, maxJpeg.height, ImageFormat.JPEG, 2)

        val bgThread = HandlerThread("KrushCameraLab").apply { start() }
        val handler = Handler(bgThread.looper)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var yuvFile: File? = null
        var jpegFile: File? = null
        var yuvThumb: File? = null
        var jpegThumb: File? = null

        val sessionReady = CountDownLatch(1)
        val sessionFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                val surfaces = listOf<Surface>(
                    yuvReader.surface,
                    jpegReader.surface
                )
                try {
                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            sessionReady.countDown()
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            sessionFailure.set(RuntimeException("onConfigureFailed"))
                            sessionReady.countDown()
                        }
                    }, handler)
                } catch (e: Exception) {
                    sessionFailure.set(e)
                    sessionReady.countDown()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                sessionFailure.set(RuntimeException("Camera error $error"))
                camera.close()
                sessionReady.countDown()
            }
        }

        try {
            manager.openCamera(id, stateCallback, handler)

            if (!sessionReady.await(10, TimeUnit.SECONDS)) {
                return Result(null, null, null, null, sb.toString() + "TIMEOUT waiting for session\n")
            }
            sessionFailure.get()?.let {
                return Result(null, null, null, null, sb.toString() + "Session failed: ${it.message}\n")
            }

            val s = session ?: return Result(null, null, null, null, sb.toString() + "Session is null\n")

            // Build a still-capture request targeting both YUV and JPEG.
            val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(yuvReader.surface)
                addTarget(jpegReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }.build()

            var yuvImage: Image? = null
            var jpegImage: Image? = null
            var yuvResult: TotalCaptureResult? = null
            var jpegResult: TotalCaptureResult? = null

            val yuvLatched = CountDownLatch(1)
            val jpegLatched = CountDownLatch(1)

            yuvReader.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(this) {
                    yuvImage?.close()
                    yuvImage = img
                }
                yuvLatched.countDown()
            }, handler)

            jpegReader.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(this) {
                    jpegImage?.close()
                    jpegImage = img
                }
                jpegLatched.countDown()
            }, handler)

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (request === req) {
                        synchronized(this) {
                            yuvResult = result
                            jpegResult = result
                        }
                    }
                }
            }

            val t0 = System.currentTimeMillis()
            s.capture(req, captureCallback, handler)

            if (!yuvLatched.await(10, TimeUnit.SECONDS)) {
                return Result(null, null, null, null, sb.toString() + "TIMEOUT waiting for YUV image\n")
            }
            if (!jpegLatched.await(10, TimeUnit.SECONDS)) {
                return Result(null, null, null, null, sb.toString() + "TIMEOUT waiting for JPEG image\n")
            }
            val captureMs = System.currentTimeMillis() - t0
            line("Capture latency (both images delivered): ${captureMs} ms")

            // --- Save YUV (as I420) ---
            val yuvImg = synchronized(this) { yuvImage }
            if (yuvImg != null) {
                val yuvMeta = describeImage(yuvImg)
                line("YUV frame: $yuvMeta")
                yuvFile = File(outputDir, "krush_lab_${ts}_maxYUV_${maxYuv.width}x${maxYuv.height}.yuv")
                writeI420(yuvImg, yuvFile)
                yuvThumb = File(outputDir, "krush_lab_${ts}_maxYUV_${maxYuv.width}x${maxYuv.height}_thumb.png")
                writeThumbnail(yuvImg, yuvThumb, 1280)
            }

            // --- Save JPEG ---
            val jpegImg = synchronized(this) { jpegImage }
            if (jpegImg != null) {
                val buf = jpegImg.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                jpegFile = File(outputDir, "krush_lab_${ts}_maxJPEG_${maxJpeg.width}x${maxJpeg.height}.jpg")
                FileOutputStream(jpegFile).use { it.write(bytes) }
                jpegThumb = File(outputDir, "krush_lab_${ts}_maxJPEG_${maxJpeg.width}x${maxJpeg.height}_thumb.png")
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val scaled = scaleBitmap(bmp, 1280)
                    FileOutputStream(jpegThumb).use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
                }
            }

            // --- Metadata dump ---
            synchronized(this) {
                yuvResult?.let { r -> line("CAPTURE RESULT:\n" + describeCaptureResult(r)) }
            }

        } catch (e: Exception) {
            line("EXCEPTION: ${e.message}")
            Log.e(TAG, "CameraLab failed", e)
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            yuvReader.close()
            jpegReader.close()
            bgThread.quitSafely()
        }

        return Result(yuvFile, jpegFile, yuvThumb, jpegThumb, sb.toString())
    }

    private fun describeImage(img: Image): String {
        val sb = StringBuilder()
        sb.append("${img.width}x${img.height} format=${img.format} ts=${img.timestamp}")
        for (i in 0 until img.planes.size) {
            val p = img.planes[i]
            sb.append(" | plane$i rowStride=${p.rowStride} pixelStride=${p.pixelStride}")
        }
        return sb.toString()
    }

    private fun describeCaptureResult(r: CaptureResult): String {
        val sb = StringBuilder()
        fun kv(k: CaptureResult.Key<*>) {
            val v = try { r.get(k) } catch (e: Exception) { null }
            sb.append("  ${k.name}: $v\n")
        }
        kv(CaptureResult.SENSOR_SENSITIVITY)
        kv(CaptureResult.SENSOR_EXPOSURE_TIME)
        kv(CaptureResult.LENS_APERTURE)
        kv(CaptureResult.LENS_FOCUS_DISTANCE)
        kv(CaptureResult.CONTROL_AF_MODE)
        kv(CaptureResult.CONTROL_AF_STATE)
        kv(CaptureResult.CONTROL_AE_MODE)
        kv(CaptureResult.CONTROL_AE_STATE)
        kv(CaptureResult.CONTROL_AWB_MODE)
        kv(CaptureResult.CONTROL_AWB_STATE)
        kv(CaptureResult.NOISE_REDUCTION_MODE)
        kv(CaptureResult.EDGE_MODE)
        kv(CaptureResult.TONEMAP_MODE)
        kv(CaptureResult.COLOR_CORRECTION_MODE)
        kv(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
        kv(CaptureResult.SENSOR_TIMESTAMP)
        kv(CaptureResult.STATISTICS_FACES)
        return sb.toString()
    }

    private fun writeI420(img: Image, out: File) {
        val w = img.width
        val h = img.height
        val yPlane = img.planes[0]
        val uPlane = img.planes[1]
        val vPlane = img.planes[2]

        val uvWidth = (w + 1) / 2
        val uvHeight = (h + 1) / 2
        val outBuf = java.nio.ByteBuffer.allocate(w * h + uvWidth * uvHeight * 2)

        // Y: copy `w` bytes per row, skipping stride padding. Guard the last row.
        val yBuf = yPlane.buffer
        val yRow = ByteArray(w)
        for (row in 0 until h) {
            val rowStart = row * yPlane.rowStride
            val available = yBuf.limit() - rowStart
            if (available <= 0) break
            val n = if (available < w) available else w
            yBuf.position(rowStart)
            yBuf.get(yRow, 0, n)
            outBuf.put(yRow, 0, n)
            // Pad to `w` in the (unlikely) case the final row is short.
            for (p in n until w) outBuf.put(0)
        }

        // U and V planes, subsampled 2x2 (planar I420 layout).
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uPs = uPlane.pixelStride
        val vPs = vPlane.pixelStride
        val uRs = uPlane.rowStride
        val vRs = vPlane.rowStride
        for (row in 0 until uvHeight) {
            val uSrc = row * uRs
            for (col in 0 until uvWidth) {
                val uIdx = uSrc + col * uPs
                outBuf.put(if (uIdx < uBuf.limit()) uBuf.get(uIdx) else 0)
            }
        }
        for (row in 0 until uvHeight) {
            val vSrc = row * vRs
            for (col in 0 until uvWidth) {
                val vIdx = vSrc + col * vPs
                outBuf.put(if (vIdx < vBuf.limit()) vBuf.get(vIdx) else 0)
            }
        }

        FileOutputStream(out).use { it.write(outBuf.array()) }
    }

    private fun writeThumbnail(img: Image, out: File, maxDim: Int) {
        val w = img.width
        val h = img.height
        val scale = Math.max(1, Math.max(w / maxDim, h / maxDim))
        val tw = w / scale
        val th = h / scale

        val yPlane = img.planes[0]
        val uPlane = img.planes[1]
        val vPlane = img.planes[2]
        val uPs = uPlane.pixelStride
        val vPs = vPlane.pixelStride
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val pixels = IntArray(tw * th)
        for (ty in 0 until th) {
            val sy = ty * scale
            for (tx in 0 until tw) {
                val sx = tx * scale
                val y = yBuf.get(sy * yPlane.rowStride + sx).toInt() and 0xFF
                val ux = sx / 2
                val uy = sy / 2
                val u = uBuf.get(uy * uPlane.rowStride + ux * uPs).toInt() and 0xFF
                val v = vBuf.get(uy * vPlane.rowStride + ux * vPs).toInt() and 0xFF

                val c = y - 16
                val d = u - 128
                val e = v - 128
                val r = clamp((298 * c + 409 * e + 128) shr 8)
                val g = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
                val b = clamp((298 * c + 516 * d + 128) shr 8)
                pixels[ty * tw + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bmp = Bitmap.createBitmap(pixels, tw, th, Bitmap.Config.ARGB_8888)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        bmp.recycle()
    }

    private fun scaleBitmap(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val scale = Math.max(1, Math.max(w / maxDim, h / maxDim))
        return Bitmap.createScaledBitmap(bmp, w / scale, h / scale, true)
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
}
