package com.krush.app

import android.content.Context
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object IspSweep {

    private const val TAG = "KrushIspSweep"

    private data class Step(
        val name: String,
        val configure: (CaptureRequest.Builder) -> Unit
    )

    fun run(context: Context, outputDir: File): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = CameraLab.rearCameraId(manager)
            ?: return "No rear camera\n"
        val ch = manager.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return "No stream map\n"
        val maxYuv = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return "No YUV sizes\n"

        line("ISP SWEEP on rear camera $id, YUV ${maxYuv.width}x${maxYuv.height}")
        line()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val yuvReader = ImageReader.newInstance(maxYuv.width, maxYuv.height, ImageFormat.YUV_420_888, 4)

        val bgThread = HandlerThread("KrushIspSweep").apply { start() }
        val handler = Handler(bgThread.looper)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val sessionReady = CountDownLatch(1)
        val sessionFailure = AtomicReference<Throwable?>(null)

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                try {
                    camera.createCaptureSession(
                        listOf(yuvReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                sessionReady.countDown()
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                sessionFailure.set(RuntimeException("onConfigureFailed"))
                                sessionReady.countDown()
                            }
                        },
                        handler
                    )
                } catch (e: Exception) {
                    sessionFailure.set(e)
                    sessionReady.countDown()
                }
            }

            override fun onDisconnected(camera: CameraDevice) { camera.close() }

            override fun onError(camera: CameraDevice, error: Int) {
                sessionFailure.set(RuntimeException("Camera error $error"))
                camera.close()
                sessionReady.countDown()
            }
        }

        try {
            manager.openCamera(id, stateCallback, handler)
            if (!sessionReady.await(10, TimeUnit.SECONDS)) {
                return "TIMEOUT opening session\n"
            }
            sessionFailure.get()?.let { return "Session failed: ${it.message}\n" }
            val s = session ?: return "Session null\n"
            val cam = device ?: return "Device null\n"

            val steps = buildSteps()

            for (step in steps) {
                val imgRef = AtomicReference<Image?>(null)
                val resultRef = AtomicReference<TotalCaptureResult?>(null)
                val latch = CountDownLatch(1)

                yuvReader.setOnImageAvailableListener({ reader ->
                    val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    imgRef.set(img)
                    latch.countDown()
                }, handler)

                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(yuvReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    step.configure(this)
                }.build()

                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        resultRef.set(result)
                    }
                }

                s.capture(req, callback, handler)

                if (!latch.await(8, TimeUnit.SECONDS)) {
                    line("${step.name}: TIMEOUT")
                    continue
                }

                val img = imgRef.get()
                if (img == null) {
                    line("${step.name}: no image")
                    continue
                }

                val safeName = step.name.replace(" ", "_").replace("/", "_")
                val yuvFile = File(outputDir, "krush_sweep_${ts}_${safeName}.yuv")
                writeI420(img, yuvFile)

                val r = resultRef.get()
                val meta = if (r != null) readback(r) else "NO RESULT"
                line("[${step.name}] -> ${yuvFile.name} (${yuvFile.length()} bytes)")
                line(meta)
                line()

                img.close()
            }
        } catch (e: Exception) {
            line("EXCEPTION: ${e.message}")
            Log.e(TAG, "sweep failed", e)
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            yuvReader.close()
            bgThread.quitSafely()
        }

        return sb.toString()
    }

    private fun buildSteps(): List<Step> = listOf(
        Step("edge_OFF") { b -> b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) },
        Step("edge_FAST") { b -> b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST) },
        Step("edge_HIGH_QUALITY") { b -> b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY) },
        Step("edge_ZSL") { b -> b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG) },

        Step("noise_OFF") { b -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF) },
        Step("noise_FAST") { b -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST) },
        Step("noise_HIGH_QUALITY") { b -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) },
        Step("noise_MINIMAL") { b -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL) },
        Step("noise_ZSL") { b -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG) },

        Step("tonemap_CONTRAST_CURVE") { b -> b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) },
        Step("tonemap_FAST") { b -> b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST) },
        Step("tonemap_HIGH_QUALITY") { b -> b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY) },

        Step("color_TRANSFORM_MATRIX") { b -> b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) },
        Step("color_FAST") { b -> b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST) },
        Step("color_HIGH_QUALITY") { b -> b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY) },

        Step("iso_100") { b -> manualIso(b, 100) },
        Step("iso_400") { b -> manualIso(b, 400) },
        Step("iso_800") { b -> manualIso(b, 800) },
        Step("iso_1600") { b -> manualIso(b, 1600) },
        Step("iso_3200") { b -> manualIso(b, 3200) }
    )

    private fun manualIso(b: CaptureRequest.Builder, iso: Int) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 33_000_000L) // ~1/30s
    }

    private fun readback(r: CaptureResult): String {
        val sb = StringBuilder()
        fun kv(k: CaptureResult.Key<*>) {
            val v = try { r.get(k) } catch (e: Exception) { null }
            sb.append("    ${k.name}: $v\n")
        }
        kv(CaptureResult.EDGE_MODE)
        kv(CaptureResult.NOISE_REDUCTION_MODE)
        kv(CaptureResult.TONEMAP_MODE)
        kv(CaptureResult.COLOR_CORRECTION_MODE)
        kv(CaptureResult.SENSOR_SENSITIVITY)
        kv(CaptureResult.SENSOR_EXPOSURE_TIME)
        kv(CaptureResult.CONTROL_AE_MODE)
        kv(CaptureResult.CONTROL_AE_STATE)
        return sb.toString().trimEnd()
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
            for (p in n until w) outBuf.put(0)
        }

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
}
