package com.krush.app

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import java.io.File

object GpuInfo {

    fun query(): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }

        line("===== GPU / SOC =====")

        // Build.SOC fields (API 31+)
        try {
            line("SoC manufacturer: ${android.os.Build.SOC_MANUFACTURER}")
            line("SoC model: ${android.os.Build.SOC_MODEL}")
        } catch (e: Throwable) {
            line("SoC info: N/A (${e.message})")
        }

        // /proc/cpuinfo
        try {
            val cpuinfo = File("/proc/cpuinfo").readText()
            var hardwareFound = false
            for (l in cpuinfo.lines()) {
                val trimmed = l.trim()
                if (trimmed.startsWith("Hardware") ||
                    trimmed.startsWith("Processor") ||
                    trimmed.startsWith("model name") ||
                    trimmed.startsWith("CPU implementer") ||
                    trimmed.startsWith("CPU part")
                ) {
                    line("CPU: $trimmed")
                    hardwareFound = true
                }
            }
            if (!hardwareFound) line("CPU: /proc/cpuinfo unreadable or empty")
        } catch (e: Exception) {
            line("CPU info: ${e.message}")
        }

        // EGL renderer/vendor
        try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                line("EGL: no display")
            } else {
                val version = IntArray(2)
                EGL14.eglInitialize(display, version, 0, version, 1)
                val configAttribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                if (numConfigs[0] > 0 && configs[0] != null) {
                    val contextAttribs = intArrayOf(
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL14.EGL_NONE
                    )
                    val context = EGL14.eglCreateContext(
                        display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
                    )
                    val surfaceAttribs = intArrayOf(
                        EGL14.EGL_WIDTH, 1,
                        EGL14.EGL_HEIGHT, 1,
                        EGL14.EGL_NONE
                    )
                    val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
                    EGL14.eglMakeCurrent(display, surface, surface, context)

                    line("GL_RENDERER: ${GLES20.glGetString(GLES20.GL_RENDERER)}")
                    line("GL_VENDOR: ${GLES20.glGetString(GLES20.GL_VENDOR)}")
                    line("GL_VERSION: ${GLES20.glGetString(GLES20.GL_VERSION)}")
                    val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS)
                    line("GL extensions: ${ext?.split(' ')?.size ?: 0}")

                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                }
                EGL14.eglTerminate(display)
            }
        } catch (e: Exception) {
            line("EGL error: ${e.message}")
        }

        line()
        return sb.toString()
    }
}
