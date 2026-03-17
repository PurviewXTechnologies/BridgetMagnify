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

    // Shader uniform handles
    private var uTexMatrix = 0
    private var uFilterMode = 0
    private var uZoom = 0
    private var uBrightness = 0
    private var uContrast = 0

    // ── Vertex Shader ────────────────────────────────────────────────────────
    // uZoom: values > 1.0 magnify by shrinking the UV window around the centre.
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

    // ── Fragment Shader ──────────────────────────────────────────────────────
    // Filter modes:
    //   0 = Normal
    //   1 = High Contrast  (threshold at 0.5 → pure black/white)
    //   2 = Inverted
    //   3 = Amber          (warm tint, useful for low-light reading)
    //   4 = Green Edge     (green-channel only — lowest power-draw per spec)
    //
    // uBrightness: additive offset on RGB  (-0.5 … +0.5)
    // uContrast  : multiplicative scale around 0.5  (default 1.0)
    //
    // NOTE: The green channel is used for the grayscale basis in filters 3 & 4
    // because the RayNeo spec explicitly says "use the green channel more and the
    // red channel less" to minimise power draw.
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

            if (uFilterMode == 1) {
                // High Contrast: hard threshold → pure black or white only
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                color = (gray > 0.5) ? vec4(1.0) : vec4(0.0, 0.0, 0.0, 1.0);
            } else if (uFilterMode == 2) {
                // Inverted
                color.rgb = 1.0 - color.rgb;
            } else if (uFilterMode == 3) {
                // Amber — warm overlay, good contrast for printed text
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                color = vec4(1.0, 0.7, 0.0, 1.0) * gray;
            } else if (uFilterMode == 4) {
                // Green Edge — green-channel only (lowest power per RayNeo spec)
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                color = vec4(0.0, gray, 0.0, 1.0);
            }

            // Brightness + Contrast post-process (applied to all modes)
            color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
            color.rgb += uBrightness;

            gl_FragColor = color;
        }
    """.trimIndent()

    init {
        // Full-screen quad: two triangles as a strip
        val quadCoords = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(quadCoords).position(0)

        val texCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
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
        uTexMatrix  = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uFilterMode = GLES20.glGetUniformLocation(program, "uFilterMode")
        uZoom       = GLES20.glGetUniformLocation(program, "uZoom")
        uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
        uContrast   = GLES20.glGetUniformLocation(program, "uContrast")

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        // Post callback to main thread once GL is ready
        Handler(Looper.getMainLooper()).post { onSurfaceCreatedCallback() }
    }

    // Camera output aspect ratio (set once we know the preview size)
    private var cameraAspectRatio = 16f / 9f  // default, updated in onSurfaceChanged

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // ── Letterbox / pillarbox to preserve camera aspect ratio ────────────
        // Without this, a 4:3 sensor output stretched into a 16:9 view makes
        // text look wide/distorted, which also hurts OCR accuracy.
        val surfaceAspect = width.toFloat() / height.toFloat()
        val camAspect     = cameraAspectRatio

        val viewW: Int
        val viewH: Int
        val offsetX: Int
        val offsetY: Int

        if (surfaceAspect > camAspect) {
            // Surface is wider than camera → pillarbox (black bars on sides)
            viewH    = height
            viewW    = (height * camAspect).toInt()
            offsetX  = (width - viewW) / 2
            offsetY  = 0
        } else {
            // Surface is taller than camera → letterbox (black bars top/bottom)
            viewW    = width
            viewH    = (width / camAspect).toInt()
            offsetX  = 0
            offsetY  = (height - viewH) / 2
        }

        GLES20.glViewport(offsetX, offsetY, viewW, viewH)
    }

    /** Called from MainActivity once the camera output size is known. */
    fun updateAspectRatio(camWidth: Int, camHeight: Int) {
        if (camHeight > 0) cameraAspectRatio = camWidth.toFloat() / camHeight.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        // ── CRITICAL: clear to pure black, NOT grey ───────────────────────────
        // On the RayNeo optical waveguide, black (#000000) is transparent.
        // Any non-zero clear colour will tint every pixel outside the camera
        // texture, reducing see-through clarity and wasting power.
        // The original 0.2f grey violated both the transparency rule and the
        // APL (Average Picture Level) < 13 % power requirement.
        GLES20.glClearColor(0f, 0f, 0f, 1.0f)
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
        // Render loop in GLTextureView handles continuous drawing; no extra
        // requestRender call needed here.
    }

    // ── Shader compilation helpers ───────────────────────────────────────────

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}