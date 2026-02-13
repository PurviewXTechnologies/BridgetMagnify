package com.siva.magnifyapp

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// UPDATED: Now implements our custom GLTextureView.Renderer
class CameraGLRenderer(private val onSurfaceCreatedCallback: () -> Unit) :
    GLTextureView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    var zoom = 1.0f
    var brightness = 0.0f
    var contrast = 1.0f
    var currentFilter = 0

    var surfaceTexture: SurfaceTexture? = null
    private var textureId = -1
    private var program = 0
    private var vertexBuffer: FloatBuffer
    private var texBuffer: FloatBuffer
    private val texMatrix = FloatArray(16)

    // Handles
    private var uTexMatrix = 0
    private var uFilterMode = 0
    private var uZoom = 0
    private var uBrightness = 0
    private var uContrast = 0

    private val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        uniform float uZoom;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vec2 center = vec2(0.5, 0.5);
            vec2 zoomedCoord = (aTexCoord - center) / uZoom + center;
            vTexCoord = (uTexMatrix * vec4(zoomedCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES sTexture;
        uniform int uFilterMode;
        uniform float uBrightness;
        uniform float uContrast;
        varying vec2 vTexCoord;
        void main() {
            vec4 color = texture2D(sTexture, vTexCoord);
            
            if (uFilterMode == 1) { // High Contrast
                 float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                 if(gray > 0.5) color = vec4(1.0); else color = vec4(0.0, 0.0, 0.0, 1.0);
            } 
            else if (uFilterMode == 2) { // Inverted
                color.rgb = 1.0 - color.rgb;
            }
            else if (uFilterMode == 3) { // Amber
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                color = vec4(1.0, 0.7, 0.0, 1.0) * gray; 
            }
            else if (uFilterMode == 4) { // Edge (Green)
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                color = vec4(0.0, gray, 0.0, 1.0);
            }

            color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
            color.rgb += uBrightness;
            gl_FragColor = color;
        }
    """.trimIndent()

    init {
        val quadCoords = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(quadCoords).position(0)

        val texCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        texBuffer.put(texCoords).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture?.setOnFrameAvailableListener(this)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) return

        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uFilterMode = GLES20.glGetUniformLocation(program, "uFilterMode")
        uZoom = GLES20.glGetUniformLocation(program, "uZoom")
        uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        Handler(Looper.getMainLooper()).post { onSurfaceCreatedCallback() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear with Dark Grey to prove Renderer is working
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(texMatrix)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uFilterMode, currentFilter)
        GLES20.glUniform1f(uZoom, zoom)
        GLES20.glUniform1f(uBrightness, brightness)
        GLES20.glUniform1f(uContrast, contrast)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        // No manual requestRender needed for our thread-based GLTextureView
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}