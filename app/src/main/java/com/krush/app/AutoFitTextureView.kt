package com.krush.app

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager

/**
 * A TextureView that fills its bounds and applies a CENTER_CROP transform:
 * the camera buffer is rotated into display orientation (using the camera's
 * SENSOR_ORIENTATION and the current display rotation), then uniformly scaled
 * so it covers the view. The image is never stretched or squashed.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var previewWidth = 0
    private var previewHeight = 0
    private var sensorOrientation = 0
    private var frontFacing = false
    private var mirror = false

    var onDebugInfo: ((String) -> Unit)? = null

    fun setCameraParams(
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientationDegrees: Int,
        isFront: Boolean,
        mirrorPreview: Boolean
    ) {
        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) {
            applyCameraParams(bufferWidth, bufferHeight, sensorOrientationDegrees, isFront, mirrorPreview)
        } else {
            post {
                applyCameraParams(bufferWidth, bufferHeight, sensorOrientationDegrees, isFront, mirrorPreview)
            }
        }
    }

    private fun applyCameraParams(
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientationDegrees: Int,
        isFront: Boolean,
        mirrorPreview: Boolean
    ) {
        previewWidth = bufferWidth
        previewHeight = bufferHeight
        sensorOrientation = sensorOrientationDegrees
        frontFacing = isFront
        mirror = mirrorPreview
        configureTransform(width, height)
    }

    private fun displayRotation(): Int = when (
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation
    ) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Always fill the parent — no letterbox bars.
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        configureTransform(w, h)
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (previewWidth == 0 || previewHeight == 0 || viewWidth == 0 || viewHeight == 0) return

        val displayDegrees = displayRotation()

        // Clockwise rotation (degrees) needed to bring the sensor buffer upright
        // on screen. The back camera subtracts the display rotation; the front
        // camera adds it (and mirrors) because it faces the user.
        val rotationDegrees = if (frontFacing) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }

        val matrix = Matrix()
        // Rotate the buffer (clockwise) into display orientation, about origin.
        matrix.setRotate(rotationDegrees.toFloat())

        // Dimensions of the buffer after rotation.
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cos = Math.abs(Math.cos(radians))
        val sin = Math.abs(Math.sin(radians))
        val rotatedW = (previewWidth * cos + previewHeight * sin).toFloat()
        val rotatedH = (previewWidth * sin + previewHeight * cos).toFloat()

        // Uniform scale to COVER the view (center crop), no distortion.
        val scale = Math.max(viewWidth / rotatedW, viewHeight / rotatedH)
        matrix.postScale(scale, scale)

        // Translate so the rotated+scaled buffer center lands at the view center.
        val bufCenter = floatArrayOf(previewWidth / 2f, previewHeight / 2f)
        val mapped = FloatArray(2)
        matrix.mapPoints(mapped, bufCenter)
        matrix.postTranslate(viewWidth / 2f - mapped[0], viewHeight / 2f - mapped[1])

        // Mirror the front camera horizontally (standard selfie preview).
        if (mirror) {
            matrix.postScale(-1f, 1f, viewWidth / 2f, viewHeight / 2f)
        }

        setTransform(matrix)

        onDebugInfo?.invoke(
            "sensor=${sensorOrientation}° display=${displayDegrees}° rot=${rotationDegrees}° " +
                "buf=${previewWidth}x${previewHeight} view=${viewWidth}x${viewHeight} " +
                "front=$frontFacing mirror=$mirror"
        )
    }
}
