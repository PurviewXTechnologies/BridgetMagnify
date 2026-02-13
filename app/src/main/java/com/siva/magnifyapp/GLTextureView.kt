package com.siva.magnifyapp

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10

class GLTextureView(context: Context, attrs: AttributeSet?) : TextureView(context, attrs), SurfaceTextureListener {

    private var renderer: Renderer? = null
    private var renderThread: RenderThread? = null

    init {
        surfaceTextureListener = this
        // This ensures the view is opaque, which is better for performance
        isOpaque = true
    }

    fun setRenderer(renderer: Renderer) {
        this.renderer = renderer
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread = RenderThread(surface, renderer!!, width, height)
        renderThread?.start()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // Handle resize if needed
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.running = false
        try {
            renderThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun onResume() {
        // If thread was stopped, restart it (logic simplified for brevity)
    }

    fun onPause() {
        renderThread?.running = false
        try {
            renderThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    interface Renderer {
        fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
        fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
        fun onDrawFrame(gl: GL10?)
    }

    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private val renderer: Renderer,
        private val width: Int,
        private val height: Int
    ) : Thread() {
        @Volatile
        var running = true

        override fun run() {
            val egl = EGLContext.getEGL() as EGL10
            val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            egl.eglInitialize(display, IntArray(2))

            // Configure EGL for OpenGLES 2.0
            val attribs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            egl.eglChooseConfig(display, attribs, configs, 1, numConfigs)
            val config = configs[0]

            val contextAttribs = intArrayOf(0x3098, 2, EGL10.EGL_NONE)
            val context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs)
            val surface = egl.eglCreateWindowSurface(display, config, surfaceTexture, null)

            egl.eglMakeCurrent(display, surface, surface, context)
            val gl = context.gl as GL10

            renderer.onSurfaceCreated(gl, config)
            renderer.onSurfaceChanged(gl, width, height)

            while (running) {
                renderer.onDrawFrame(gl)
                egl.eglSwapBuffers(display, surface)
                try {
                    sleep(16) // ~60 FPS
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }

            egl.eglDestroySurface(display, surface)
            egl.eglDestroyContext(display, context)
            egl.eglTerminate(display)
        }
    }
}