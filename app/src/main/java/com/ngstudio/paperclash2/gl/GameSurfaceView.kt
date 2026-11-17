package com.ngstudio.paperclash2.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import com.ngstudio.paperclash2.game.GameController
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** The one GL surface of the app; it sits behind all Compose UI. */
@SuppressLint("ViewConstructor")
class GameSurfaceView(context: Context, controller: GameController, msaa: Boolean) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(ConfigChooser(msaa))
        preserveEGLContextOnPause = true
        setRenderer(controller)
        renderMode = RENDERMODE_CONTINUOUSLY
        controller.surface = this
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        // A steady 60 fps looks smoother than hopping between 60 and 120 and saves battery.
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (_: Exception) {
            }
        }
    }

    /** RGB888 + 16-bit depth, 4× MSAA when asked for and available, ES 3 renderable. */
    private class ConfigChooser(private val msaa: Boolean) : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            if (msaa) pick(egl, display, 4)?.let { return it }
            pick(egl, display, 0)?.let { return it }
            val any = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            egl.eglChooseConfig(display, intArrayOf(EGL10.EGL_RENDERABLE_TYPE, ES3_BIT, EGL10.EGL_NONE), any, 1, n)
            return any[0] ?: throw IllegalStateException("No OpenGL ES 3 config")
        }

        private fun pick(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
            val attrs = if (samples > 0) intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, ES3_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE,
            ) else intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, ES3_BIT, EGL10.EGL_NONE,
            )
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, attrs, null, 0, count) || count[0] == 0) return null
            val configs = arrayOfNulls<EGLConfig>(count[0])
            egl.eglChooseConfig(display, attrs, configs, count[0], count)
            // prefer an exact 8-8-8 match
            val v = IntArray(1)
            for (c in configs) {
                if (c == null) continue
                egl.eglGetConfigAttrib(display, c, EGL10.EGL_RED_SIZE, v)
                val r = v[0]
                egl.eglGetConfigAttrib(display, c, EGL10.EGL_GREEN_SIZE, v)
                val g = v[0]
                egl.eglGetConfigAttrib(display, c, EGL10.EGL_BLUE_SIZE, v)
                if (r == 8 && g == 8 && v[0] == 8) return c
            }
            return configs.firstOrNull()
        }

        companion object {
            private const val ES3_BIT = 0x40
        }
    }
}
