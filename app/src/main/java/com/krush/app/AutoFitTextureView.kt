package com.krush.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager

class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    private var previewWidth = 0
    private var previewHeight = 0
    private var sensorOrientation = 0
    private var mirror = false

    fun setAspectRatio(width: Int, height: Int) {
        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) {
            applyAspectRatio(width, height)
        } else {
            post { applyAspectRatio(width, height) }
        }
    }

    private fun applyAspectRatio(width: Int, height: Int) {
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    /**
     * Configure the view for the given camera preview buffer (always in sensor/landscape
     * orientation) plus the sensor rotation. The view aspect ratio is derived from the
     * buffer rotated into display orientation so the preview is never stretched.
     */
    fun setCameraParams(bufferWidth: Int, bufferHeight: Int, sensorOrientationDegrees: Int, mirrorPreview: Boolean) {
        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) {
            applyCameraParams(bufferWidth, bufferHeight, sensorOrientationDegrees, mirrorPreview)
        } else {
            post { applyCameraParams(bufferWidth, bufferHeight, sensorOrientationDegrees, mirrorPreview) }
        }
    }

    private fun applyCameraParams(bufferWidth: Int, bufferHeight: Int, sensorOrientationDegrees: Int, mirrorPreview: Boolean) {
        previewWidth = bufferWidth
        previewHeight = bufferHeight
        sensorOrientation = sensorOrientationDegrees
        mirror = mirrorPreview

        val rotation = displayRotation()
        val swapped = (sensorOrientationDegrees + rotation) % 180 != 0
        if (swapped) {
            applyAspectRatio(bufferHeight, bufferWidth)
        } else {
            applyAspectRatio(bufferWidth, bufferHeight)
        }
        configureTransform(width, height)
    }

    private fun displayRotation(): Int = when ((context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        ?.defaultDisplay?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width.toLong() * ratioHeight < height.toLong() * ratioWidth) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        configureTransform(w, h)
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (previewWidth == 0 || previewHeight == 0 || viewWidth == 0 || viewHeight == 0) return

        val rotation = displayRotation()
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewHeight,
                viewWidth.toFloat() / previewWidth
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }

        if (mirror) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }

        setTransform(matrix)
    }
}
