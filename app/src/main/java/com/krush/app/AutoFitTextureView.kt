package com.krush.app

import android.content.Context
import android.graphics.Matrix
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
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    /**
     * Configure the view for the given camera buffer (always in sensor/landscape
     * orientation) plus the sensor rotation. Applies the correct rotation matrix so
     * the preview is never stretched, and picks the correct displayed aspect ratio.
     */
    fun setCameraParams(bufferWidth: Int, bufferHeight: Int, sensorOrientationDegrees: Int, mirrorPreview: Boolean) {
        previewWidth = bufferWidth
        previewHeight = bufferHeight
        sensorOrientation = sensorOrientationDegrees
        mirror = mirrorPreview

        val degrees = (sensorOrientationDegrees - displayDegrees() + 360) % 360
        if (degrees == 90 || degrees == 270) {
            setAspectRatio(bufferHeight, bufferWidth)
        } else {
            setAspectRatio(bufferWidth, bufferHeight)
        }
    }

    private fun displayDegrees(): Int {
        val rotation = when (val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) {
            null -> return 0
            else -> wm.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * ratioWidth / ratioHeight) {
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
        if (previewWidth == 0 || viewWidth == 0) return

        val degrees = (sensorOrientation - displayDegrees() + 360) % 360
        val matrix = Matrix()
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f

        when (degrees) {
            0 -> { /* no rotation */ }
            90 -> matrix.postRotate(90f, cx, cy)
            180 -> matrix.postRotate(180f, cx, cy)
            270 -> matrix.postRotate(270f, cx, cy)
        }

        if (mirror) {
            matrix.postScale(-1f, 1f, cx, cy)
        }

        setTransform(matrix)
    }
}
