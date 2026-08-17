package com.krush.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CAMERA = 200
    }

    private lateinit var controller: CameraController
    private lateinit var isoLabel: TextView
    private lateinit var shutterLabel: TextView
    private lateinit var evLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startCamera()
    }

    private fun startCamera() {
        val textureView = findViewById<TextureView>(com.krush.app.R.id.camera_preview)
        controller = CameraController(this, textureView)

        if (textureView.isAvailable) {
            controller.init { ok ->
                runOnUiThread {
                    if (!ok) Toast.makeText(this, "Camera init failed", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    controller.init { ok ->
                        runOnUiThread {
                            if (!ok) Toast.makeText(this@CameraActivity, "Camera init failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {}

                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun buildUi(): android.view.View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val texture = TextureView(this)
        texture.id = com.krush.app.R.id.camera_preview
        texture.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(texture)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.TOP }

        isoLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000.toInt())
            setPadding(16, 8, 16, 8)
        }
        top.addView(isoLabel)

        val isoSeek = SeekBar(this)
        isoSeek.max = 15900
        isoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                controller.iso = 100 + progress
                controller.manualExposure = true
                updateLabels()
                controller.applySettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        top.addView(isoSeek)

        shutterLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000.toInt())
            setPadding(16, 8, 16, 8)
        }
        top.addView(shutterLabel)

        val shutterSeek = SeekBar(this)
        shutterSeek.max = 1000
        shutterSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // map 0..1000 -> 1/10000s .. 1/2s (ns), log-ish via linear on fraction
                val frac = progress / 1000.0
                val ns = (100_000L + frac * 500_000_000L).toLong()
                controller.shutterNs = ns
                controller.manualExposure = true
                updateLabels()
                controller.applySettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        top.addView(shutterSeek)

        evLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000.toInt())
            setPadding(16, 8, 16, 8)
        }
        top.addView(evLabel)

        val evSeek = SeekBar(this)
        evSeek.max = 40
        evSeek.progress = 20
        evSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                controller.ev = progress - 20
                controller.manualExposure = false
                updateLabels()
                controller.applySettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        top.addView(evSeek)
        root.addView(top)

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = android.view.Gravity.CENTER
        bottom.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.BOTTOM }
        bottom.setPadding(32, 32, 32, 64)

        fun btn(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(0x88000000.toInt())
                setPadding(32, 24, 32, 24)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 24
                layoutParams = lp
                setOnClickListener { onClick() }
            }
        }

        bottom.addView(btn("⟲") {
            controller.switchCamera { ok ->
                runOnUiThread { if (!ok) Toast.makeText(this, "No front camera", Toast.LENGTH_SHORT).show() }
            }
        })
        bottom.addView(btn("⚡") {
            controller.flashOn = !controller.flashOn
            controller.applySettings()
        })
        bottom.addView(btn("●") {
            controller.capture { ok ->
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Saved" else "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(bottom)

        updateLabels()
        return root
    }

    private fun updateLabels() {
        if (!::controller.isInitialized) return
        isoLabel.text = if (controller.manualExposure) "ISO ${controller.iso}" else "ISO AUTO"
        shutterLabel.text = if (controller.manualExposure)
            "SHUTTER 1/${(1_000_000_000L / controller.shutterNs).toInt()}s"
        else "SHUTTER AUTO"
        evLabel.text = "EV ${controller.ev / 10.0}"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controller.isInitialized) controller.close()
    }
}
