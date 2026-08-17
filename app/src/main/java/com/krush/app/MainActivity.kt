package com.krush.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KrushCapability"
        private const val REQ_CAMERA = 100
    }

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        output = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(output)
        setContentView(scroll)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            runScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runScan()
            } else {
                output.text = "CAMERA permission denied. Cannot scan capabilities."
            }
        }
    }

    private fun runScan() {
        output.text = "Scanning camera capabilities + running camera lab..."
        Thread {
            val sb = StringBuilder()
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

                sb.append(CapabilityScanner.deviceInfo())
                sb.append(GpuInfo.query())
                sb.append(CapabilityScanner.memoryInfo(am))
                sb.append(CapabilityScanner.scan(manager))
                sb.append(CameraXExtensionScanner.scan(this))

                // Camera Lab: capture one max-YUV frame + max-JPEG.
                val outDir = getExternalFilesDir(null) ?: filesDir
                val labDir = File(outDir, "lab")
                if (!labDir.exists()) labDir.mkdirs()
                val labResult = CameraLab.run(this, labDir)
                sb.append("===== CAMERA LAB =====\n")
                sb.append(labResult.log)
                if (labResult.yuvFile != null) sb.append("YUV saved: ${labResult.yuvFile.name}\n")
                if (labResult.jpegFile != null) sb.append("JPEG saved: ${labResult.jpegFile.name}\n")
                if (labResult.yuvThumbFile != null) sb.append("YUV thumb: ${labResult.yuvThumbFile.name}\n")
                if (labResult.jpegThumbFile != null) sb.append("JPEG thumb: ${labResult.jpegThumbFile.name}\n")
            } catch (e: Exception) {
                sb.append("FATAL: ").append(e.message).append('\n')
            }

            val report = sb.toString()
            Log.i(TAG, "\n$report")

            val file = saveReport(report)
            runOnUiThread {
                output.text = report +
                    "\n\n===== REPORT SAVED TO =====\n" +
                    (file?.absolutePath ?: "FAILED to save")
                Toast.makeText(this, "Report saved: ${file?.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun saveReport(report: String): File? {
        return try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "krush_capability_report_$ts.txt")
            file.writeText(report)
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save report", e)
            null
        }
    }
}
