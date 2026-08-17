package com.krush.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.krush.app.CameraController.Companion.Aspect

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CAMERA = 200

        private enum class Mode(val label: String) {
            PHOTO("PHOTO"),
            VIDEO("VIDEO"),
            PORTRAIT("PORTRAIT"),
            NIGHT("NIGHT"),
            PRO("PRO")
        }
    }

    private lateinit var controller: CameraController
    private lateinit var resolutionText: TextView
    private lateinit var aspectRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var proPanel: LinearLayout
    private lateinit var isoLabel: TextView
    private lateinit var shutterLabel: TextView
    private lateinit var evLabel: TextView
    private lateinit var shutterCore: View
    private lateinit var btnFlash: TextView
    private lateinit var btnHdr: TextView
    private lateinit var recTimer: TextView

    private var mode = Mode.PHOTO
    private var proPanelVisible = false
    private var hdrOn = false
    private var aspectChips = mutableListOf<TextView>()
    private var modeChips = mutableListOf<TextView>()

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimer()
            recTimer.postDelayed(this, 30)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        bindViews()
        initController()
        buildAspectChips()
        buildModeChips()
        wireSeekBars()
        wireButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQ_CAMERA
            )
            return
        }
        startCamera()
    }

    private fun initController() {
        val texture = findViewById<AutoFitTextureView>(R.id.camera_preview)
        controller = CameraController(this, texture)
        val debug = findViewById<TextView>(R.id.debug_overlay)
        texture.onDebugInfo = { info ->
            runOnUiThread {
                debug.text = info
                debug.visibility = View.VISIBLE
            }
        }
    }

    private fun bindViews() {
        resolutionText = findViewById(R.id.resolution_text)
        aspectRow = findViewById(R.id.aspect_row)
        modeRow = findViewById(R.id.mode_row)
        proPanel = findViewById(R.id.pro_panel)
        isoLabel = findViewById(R.id.iso_label)
        shutterLabel = findViewById(R.id.shutter_label)
        evLabel = findViewById(R.id.ev_label)
        shutterCore = findViewById(R.id.shutter_core)
        btnFlash = findViewById(R.id.btn_flash)
        btnHdr = findViewById(R.id.btn_hdr)
        recTimer = findViewById(R.id.rec_timer)
    }

    private fun startCamera() {
        val texture = findViewById<AutoFitTextureView>(R.id.camera_preview)

        val start = {
            controller.init { err ->
                runOnUiThread {
                    if (err != null) Toast.makeText(this, "Camera init failed: $err", Toast.LENGTH_LONG).show()
                    updateResolutionText()
                }
            }
        }

        if (texture.isAvailable) {
            start()
        } else {
            texture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: android.graphics.SurfaceTexture, width: Int, height: Int
                ) { start() }

                override fun onSurfaceTextureSizeChanged(
                    surface: android.graphics.SurfaceTexture, width: Int, height: Int
                ) {}

                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
        }
    }

    private fun buildAspectChips() {
        for (aspect in Aspect.values()) {
            val chip = makeChip(aspect.label)
            chip.setOnClickListener {
                controller.setAspect(aspect)
                updateAspectChips()
                updateResolutionText()
            }
            aspectRow.addView(chip)
            aspectChips.add(chip)
        }
        updateAspectChips()
    }

    private fun buildModeChips() {
        for (m in Mode.values()) {
            val chip = makeChip(m.label)
            chip.setOnClickListener {
                mode = m
                updateModeChips()
                updateResolutionText()
            }
            modeRow.addView(chip)
            modeChips.add(chip)
        }
        updateModeChips()
    }

    private fun makeChip(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(24, 12, 24, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 10
            layoutParams = lp
        }
    }

    private fun updateAspectChips() {
        if (!::controller.isInitialized) return
        for ((i, chip) in aspectChips.withIndex()) {
            val active = i == Aspect.values().indexOf(controller.currentAspect)
            if (active) {
                chip.setBackgroundResource(R.drawable.chip_selected)
                chip.setTextColor(Color.BLACK)
            } else {
                chip.setBackgroundResource(R.drawable.chip_bg)
                chip.setTextColor(Color.WHITE)
            }
        }
    }

    private fun updateModeChips() {
        for ((i, chip) in modeChips.withIndex()) {
            val active = i == Mode.values().indexOf(mode)
            if (active) {
                chip.setBackgroundResource(R.drawable.chip_selected)
                chip.setTextColor(Color.BLACK)
            } else {
                chip.setBackgroundResource(R.drawable.chip_bg)
                chip.setTextColor(Color.WHITE)
            }
        }
    }

    private fun wireSeekBars() {
        val isoSeek = findViewById<SeekBar>(R.id.iso_seek)
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

        val shutterSeek = findViewById<SeekBar>(R.id.shutter_seek)
        shutterSeek.max = 1000
        shutterSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
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

        val evSeek = findViewById<SeekBar>(R.id.ev_seek)
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
    }

    private fun wireButtons() {
        findViewById<View>(R.id.shutter_ring).setOnClickListener { onShutterClick() }
        shutterCore.setOnClickListener { onShutterClick() }

        findViewById<View>(R.id.btn_switch).setOnClickListener {
            controller.switchCamera { ok ->
                runOnUiThread {
                    if (!ok) Toast.makeText(this, "No front camera", Toast.LENGTH_SHORT).show()
                    updateResolutionText()
                }
            }
        }

        btnFlash.setOnClickListener {
            controller.flashOn = !controller.flashOn
            btnFlash.alpha = if (controller.flashOn) 1.0f else 0.4f
            controller.applySettings()
        }

        btnHdr.setOnClickListener {
            hdrOn = !hdrOn
            btnHdr.alpha = if (hdrOn) 1.0f else 0.4f
            // HDR is a future multi-frame capture; placeholder toggle for now.
            Toast.makeText(this, if (hdrOn) "HDR ON" else "HDR OFF", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            proPanelVisible = !proPanelVisible
            proPanel.visibility = if (proPanelVisible) View.VISIBLE else View.GONE
        }

        controller.onRecordingStateChanged = { recording ->
            runOnUiThread {
                if (recording) {
                    (shutterCore.background as? GradientDrawable)?.setColor(Color.RED)
                } else {
                    shutterCore.setBackgroundResource(R.drawable.shutter_core)
                }
            }
        }
    }

    private fun onShutterClick() {
        if (mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.NIGHT || mode == Mode.PRO) {
            controller.capture { ok ->
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Saved" else "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            when {
                !controller.isRecording() -> {
                    controller.startRecording { ok ->
                        runOnUiThread {
                            if (!ok) Toast.makeText(this, "Record failed", Toast.LENGTH_SHORT).show()
                            else startTimer()
                        }
                    }
                }
                controller.isPaused() -> {
                    controller.resumeRecording { _ ->
                        runOnUiThread { startTimer() }
                    }
                }
                else -> {
                    // Recording and not paused -> stop.
                    controller.stopRecording { path ->
                        runOnUiThread {
                            stopTimer()
                            Toast.makeText(this, if (path != null) "Video saved" else "Stop failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun startTimer() {
        recTimer.visibility = View.VISIBLE
        recTimer.removeCallbacks(timerRunnable)
        recTimer.post(timerRunnable)
    }

    private fun stopTimer() {
        recTimer.removeCallbacks(timerRunnable)
        recTimer.visibility = View.GONE
    }

    private fun updateTimer() {
        val ms = controller.recordedDurationMs()
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val cs = (ms % 1000) / 10
        val paused = controller.isPaused()
        val prefix = if (paused) "PAUSED" else "REC ●"
        recTimer.text = String.format("%s %02d:%02d.%02d", prefix, min, sec, cs)
    }

    private fun updateLabels() {
        if (!::controller.isInitialized) return
        isoLabel.text = if (controller.manualExposure) "ISO ${controller.iso}" else "ISO AUTO"
        shutterLabel.text = if (controller.manualExposure)
            "SHUTTER 1/${(1_000_000_000L / controller.shutterNs).toInt()}s"
        else "SHUTTER AUTO"
        evLabel.text = "EV ${controller.ev / 10.0}"
    }

    private fun updateResolutionText() {
        if (!::controller.isInitialized) return
        val res = controller.currentPhotoSize()
        val aspectLabel = controller.currentPhotoAspectLabel()
        resolutionText.text = "$res · $aspectLabel"
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

    override fun onDestroy() {
        super.onDestroy()
        recTimer.removeCallbacks(timerRunnable)
        if (::controller.isInitialized) controller.close()
    }
}
