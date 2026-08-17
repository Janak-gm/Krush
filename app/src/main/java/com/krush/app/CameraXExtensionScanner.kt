package com.krush.app

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider

object CameraXExtensionScanner {

    fun scan(context: Context): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }

        line("===== CAMERAX EXTENSIONS =====")
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val extensionsManager = ExtensionsManager.getInstanceAsync(context, cameraProvider).get()

            val selectors = listOf(
                "BACK" to CameraSelector.DEFAULT_BACK_CAMERA,
                "FRONT" to CameraSelector.DEFAULT_FRONT_CAMERA
            )

            for ((label, selector) in selectors) {
                line("Lens facing $label:")
                val modes = listOf(
                    ExtensionMode.NONE,
                    ExtensionMode.BOKEH,
                    ExtensionMode.HDR,
                    ExtensionMode.NIGHT,
                    ExtensionMode.FACE_RETOUCH,
                    ExtensionMode.AUTO
                )
                for (mode in modes) {
                    val name = extensionModeName(mode)
                    val available = try {
                        extensionsManager.isExtensionAvailable(selector, mode)
                    } catch (e: Exception) {
                        false
                    }
                    line("  $name: ${if (available) "YES" else "NO"}")
                }
            }
        } catch (e: Exception) {
            line("ERROR scanning CameraX extensions: ${e.message}")
        }
        line()
        return sb.toString()
    }

    private fun extensionModeName(mode: Int): String = when (mode) {
        ExtensionMode.NONE -> "NONE"
        ExtensionMode.BOKEH -> "BOKEH (portrait)"
        ExtensionMode.HDR -> "HDR"
        ExtensionMode.NIGHT -> "NIGHT"
        ExtensionMode.FACE_RETOUCH -> "FACE_RETOUCH"
        ExtensionMode.AUTO -> "AUTO"
        else -> "UNKNOWN($mode)"
    }
}
