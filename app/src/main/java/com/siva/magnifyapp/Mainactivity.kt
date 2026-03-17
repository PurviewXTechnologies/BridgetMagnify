package com.siva.magnifyapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.transition.TransitionManager
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.siva.magnifyapp.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    // ── Camera ───────────────────────────────────────────────────────────────
    private lateinit var renderer: CameraGLRenderer
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var sensorOrientation = 0

    // ── Modes ────────────────────────────────────────────────────────────────
    private enum class Mode { ZOOM, FILTER, BRIGHTNESS, OCR_OFFLINE, OCR_ONLINE }
    private var currentMode = Mode.ZOOM
    private lateinit var filterNames: List<String>

    // ── OCR state machine ────────────────────────────────────────────────────
    private enum class OcrSubState { SCANNING, FROZEN }
    private var ocrSubState = OcrSubState.SCANNING
    private var isProcessingOcr = false

    // ML Kit text recognizer (Offline, on-device)
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Gemini API (Online)
    private val geminiModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = "AIzaSyCvc7FZb8b7EnhORVsFiQqblG5wYnr2ceY"
        )
    }

    private var currentDisplayedText = ""

    // Font size
    private var ocrFontSize = 28f
    private val OCR_FONT_MIN  = 20f
    private val OCR_FONT_MAX  = 52f
    private val OCR_FONT_STEP = 4f

    // ── Text-to-Speech ───────────────────────────────────────────────────────
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var lastSpokenText = ""

    // ── Haptics ──────────────────────────────────────────────────────────────
    private var vibrator: Vibrator? = null

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val TAG = "MagnifyApp"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        filterNames = listOf(
            getString(R.string.filter_normal),
            getString(R.string.filter_high_contrast),
            getString(R.string.filter_inverted),
            getString(R.string.filter_amber),
            getString(R.string.filter_green_edge)
        )

        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator

        initTts()
        configureDualEyeDisplay()

        renderer = CameraGLRenderer {
            if (allPermissionsGranted()) startCamera()
            else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        mBindingPair.right.glTextureView.setRenderer(renderer)

        listenToTempleEvents()
        updateUI()
    }

    override fun onPause() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close();   cameraDevice   = null
            imageReader?.close();    imageReader    = null
            mBindingPair.right.glTextureView.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera in onPause", e)
        }
        super.onPause()
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TTS
    // ══════════════════════════════════════════════════════════════════════════

    private fun initTts() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (isTtsReady) {
                    textToSpeech?.setSpeechRate(0.85f)
                    textToSpeech?.setPitch(1.0f)
                }
            }
        }
    }

    private fun speakIfNew(text: String) {
        if (!isTtsReady || text.isBlank() || text == lastSpokenText) return
        lastSpokenText = text
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ocr_utterance")

        runOnUiThread { mBindingPair.updateView { ttsBadge?.visibility = View.VISIBLE } }
        lifecycleScope.launch {
            val duration = (text.length * 85L).coerceIn(1500L, 15_000L)
            delay(duration)
            runOnUiThread { mBindingPair.updateView { ttsBadge?.visibility = View.GONE } }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Haptic feedback
    // ══════════════════════════════════════════════════════════════════════════

    private fun vibrateCapture() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(60)
            }
        } catch (_: Exception) {}
    }

    private fun vibrateSuccess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 40, 60, 40), -1)
            }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  OCR
    // ══════════════════════════════════════════════════════════════════════════

    private fun triggerFreezeCapture() {
        if (isProcessingOcr) return
        vibrateCapture()

        ocrSubState = OcrSubState.FROZEN

        // Show processing badge immediately
        runOnUiThread {
            mBindingPair.updateView {
                ocrStatusBadge?.text = if (currentMode == Mode.OCR_ONLINE) "⏸ PROCESSING (ONLINE)…" else "⏸ PROCESSING…"
                ocrStatusBadge?.setTextColor(Color.parseColor("#FAC775"))  // amber
                ocrStatusBadge?.visibility = View.VISIBLE
            }
        }

        takeHighResPicture()
    }

    private fun takeHighResPicture() {
        try {
            if (cameraDevice == null || imageReader == null) {
                fallbackToScreenBitmap()
                return
            }
            val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            imageReader?.surface?.let { req?.addTarget(it) }
            req?.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            captureSession?.capture(req!!.build(), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "High-res capture failed", e)
            fallbackToScreenBitmap()
        }
    }

    private fun fallbackToScreenBitmap() {
        val bmp = mBindingPair.right.glTextureView.bitmap
        if (bmp == null) {
            Log.w(TAG, "Freeze capture: bitmap not available")
            resetToReadyState()
            return
        }
        if (currentMode == Mode.OCR_ONLINE) {
            runGeminiOcr(bmp)
        } else {
            runOcr(bmp)
        }
    }

    private fun runOcr(bitmap: Bitmap) {
        isProcessingOcr = true
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText: Text ->
                    val isOcrMode = currentMode == Mode.OCR_OFFLINE || currentMode == Mode.OCR_ONLINE
                    if (isOcrMode) {
                        val sortedBlocks = visionText.textBlocks.sortedWith(
                            compareBy<Text.TextBlock> {
                                it.boundingBox?.top?.div(50)
                            }.thenBy { it.boundingBox?.left }
                        )
                        val fullText = sortedBlocks.joinToString("\n") { it.text }.trim()
                        handleFinalOcrText(fullText)
                    }
                }
                .addOnFailureListener { e: Exception ->
                    Log.e(TAG, "OCR failed", e)
                    resetToReadyState()
                }
                .addOnCompleteListener { isProcessingOcr = false }
        } catch (e: Exception) {
            Log.e(TAG, "runOcr error", e)
            isProcessingOcr = false
            resetToReadyState()
        }
    }

    private fun runGeminiOcr(bitmap: Bitmap) {
        isProcessingOcr = true
        lifecycleScope.launch {
            try {
                val response = geminiModel.generateContent(
                    content {
                        image(bitmap)
                        text("Extract all the text from this image. Only return the extracted text, nothing else. If there is no text, reply with exactly 'No text detected.'")
                    }
                )
                val fullText = response.text?.trim() ?: ""
                handleFinalOcrText(fullText)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini OCR failed", e)
                handleFinalOcrText("Error reaching Gemini: ${e.message}")
            } finally {
                isProcessingOcr = false
            }
        }
    }

    private fun handleFinalOcrText(fullText: String) {
        val displayText = if (fullText.isEmpty()) "No text detected." else fullText
        currentDisplayedText = displayText

        val wordCount = displayText.split("\\s+".toRegex()).count { it.isNotBlank() }

        runOnUiThread {
            showOcrResultPanel()
            mBindingPair.updateView {
                ocrTextView?.apply {
                    text = currentDisplayedText
                    textSize = ocrFontSize
                }
                ocrWordCount?.text = "$wordCount words"
                ocrStatusBadge?.apply {
                    val noText = fullText.isEmpty() || fullText == "No text detected."
                    text = if (noText) "✗ NO TEXT" else "✓ $wordCount WORDS"
                    setTextColor(
                        if (noText) Color.parseColor("#F09595")
                        else Color.parseColor("#4DE2FF")
                    )
                    visibility = View.VISIBLE
                }
                ocrScrollView?.post { ocrScrollView?.scrollTo(0, 0) }
            }
        }

        if (fullText.isNotEmpty() && fullText != "No text detected.") {
            vibrateSuccess()
            lifecycleScope.launch { speakIfNew(currentDisplayedText) }
        } else {
            try {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 50, 50, 50), -1)
            } catch (_: Exception) {}
        }
    }

    private fun resetToReadyState() {
        ocrSubState = OcrSubState.SCANNING
        currentDisplayedText = ""
        textToSpeech?.stop()
        lastSpokenText = ""
        
        runOnUiThread {
            mBindingPair.updateView {
                ocrStatusBadge?.text = "● READY"
                ocrStatusBadge?.setTextColor(Color.parseColor("#44FF66"))
                ocrStatusBadge?.visibility = View.VISIBLE
            }
            hideOcrResultPanel()
        }
    }

    private fun clearOcrPanel() {
        runOnUiThread {
            mBindingPair.updateView {
                ocrTextView?.text = ""
                ocrWordCount?.text = "–"
                ocrOverlayContainer?.removeAllViews()
                ocrStatusBadge?.visibility = View.GONE
                ttsBadge?.visibility = View.GONE
            }
            hideOcrResultPanel()
        }
        currentDisplayedText = ""
        textToSpeech?.stop()
        lastSpokenText = ""
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  OCR Layout transitions
    // ══════════════════════════════════════════════════════════════════════════

    private fun showOcrResultPanel() {
        val rootView = mBindingPair.right.root
        if (rootView is ViewGroup) TransitionManager.beginDelayedTransition(rootView)

        mBindingPair.right.viewfinderContainer?.let { vf ->
            (vf.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.weight = 2f; vf.layoutParams = lp
            }
        }
        mBindingPair.right.ocrResultPanel?.let { panel ->
            (panel.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.weight = 4f; panel.layoutParams = lp
            }
            panel.visibility = View.VISIBLE
        }
    }

    private fun hideOcrResultPanel() {
        val rootView = mBindingPair.right.root
        if (rootView is ViewGroup) TransitionManager.beginDelayedTransition(rootView)

        mBindingPair.right.viewfinderContainer?.let { vf ->
            (vf.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.weight = 4f; vf.layoutParams = lp
            }
        }
        mBindingPair.right.ocrResultPanel?.let { panel ->
            (panel.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.weight = 0f; panel.layoutParams = lp
            }
            panel.visibility = View.GONE
        }
    }

    private fun enterOcrLayout() {
        runOnUiThread {
            mBindingPair.right.instructionText?.visibility = View.GONE
            mBindingPair.right.ocrHintText?.visibility     = View.VISIBLE
            mBindingPair.right.tvValue?.visibility         = View.GONE
            mBindingPair.right.progressBar?.visibility     = View.GONE

            mBindingPair.updateView {
                ocrStatusBadge?.text = "● READY"
                ocrStatusBadge?.setTextColor(Color.parseColor("#44FF66"))
                ocrStatusBadge?.visibility = View.VISIBLE
            }
            hideOcrResultPanel()
        }
    }

    private fun exitOcrLayout() {
        runOnUiThread {
            mBindingPair.right.instructionText?.visibility = View.VISIBLE
            mBindingPair.right.ocrHintText?.visibility     = View.GONE
            
            mBindingPair.updateView {
                ocrStatusBadge?.visibility = View.GONE
            }
            hideOcrResultPanel()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Dual-Eye Display
    // ══════════════════════════════════════════════════════════════════════════

    private fun configureDualEyeDisplay() {
        runOnUiThread {
            mBindingPair.left.contentLayout?.visibility = View.GONE
            mBindingPair.left.mirrorView.visibility     = View.VISIBLE

            mBindingPair.right.contentLayout?.visibility = View.VISIBLE
            mBindingPair.right.mirrorView.visibility     = View.GONE

            try {
                mBindingPair.left.mirrorView.setSource(mBindingPair.right.root)
                mBindingPair.left.mirrorView.startMirroring()
            } catch (e: Exception) {
                Log.e(TAG, "Mirror setup error", e)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Camera (Camera2)
    // ══════════════════════════════════════════════════════════════════════════

    private fun startCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            var backId: String? = null
            for (id in manager.cameraIdList) {
                if (manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK) {
                    backId = id; break
                }
            }
            val finalId = backId ?: manager.cameraIdList[0]
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            manager.openCamera(finalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cameraDevice = camera; startPreview() }
                override fun onDisconnected(camera: CameraDevice) { cameraDevice?.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error"); cameraDevice?.close(); cameraDevice = null
                }
            }, null)
        } catch (e: Exception) { Log.e(TAG, "startCamera failed", e) }
    }

    private fun startPreview() {
        try {
            val texture = renderer.surfaceTexture ?: return
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val chars  = manager.getCameraCharacteristics(cameraDevice!!.id)
            val map    = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val bestSize = map?.getOutputSizes(SurfaceTexture::class.java)
                ?.maxByOrNull { it.width * it.height }
            val width  = bestSize?.width  ?: 1920
            val height = bestSize?.height ?: 1080
            Log.d(TAG, "Preview: ${width}×${height}")

            texture.setDefaultBufferSize(width, height)
            renderer.updateAspectRatio(width, height)
            val surface = Surface(texture)

            // Setup high-res ImageReader for OCR
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
            val bestJpegSize = jpegSizes?.maxByOrNull { it.width * it.height }
            val jpegWidth = bestJpegSize?.width ?: width
            val jpegHeight = bestJpegSize?.height ?: height
            
            imageReader = ImageReader.newInstance(jpegWidth, jpegHeight, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        // Crop to match digital zoom
                        val zoom = renderer.zoom
                        val cropWidth = (bitmap.width / zoom).toInt()
                        val cropHeight = (bitmap.height / zoom).toInt()
                        val startX = (bitmap.width - cropWidth) / 2
                        val startY = (bitmap.height - cropHeight) / 2
                        
                        val matrix = Matrix()
                        if (sensorOrientation != 0) {
                            matrix.postRotate(sensorOrientation.toFloat())
                        }
                        
                        val finalBitmap = Bitmap.createBitmap(bitmap, startX, startY, cropWidth, cropHeight, matrix, true)
                        
                        if (currentMode == Mode.OCR_ONLINE) {
                            runGeminiOcr(finalBitmap)
                        } else {
                            runOcr(finalBitmap)
                        }
                    } else {
                        runOnUiThread { fallbackToScreenBitmap() }
                    }
                }
            }, null)

            val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            req?.addTarget(surface)

            req?.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            req?.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            req?.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)

            val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            if (awbModes?.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO) == true)
                req?.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            val shadingModes = chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
            if (shadingModes?.contains(CaptureRequest.SHADING_MODE_HIGH_QUALITY) == true)
                req?.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)

            val tmModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            if (tmModes?.contains(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY) == true)
                req?.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)

            val SCENE_MODE_DOCUMENT = 18
            val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            if (sceneModes?.contains(SCENE_MODE_DOCUMENT) == true)
                req?.set(CaptureRequest.CONTROL_SCENE_MODE, SCENE_MODE_DOCUMENT)
            else
                req?.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            req?.set(CaptureRequest.CONTROL_AF_MODE, when {
                afModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true ->
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes?.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) == true ->
                    CaptureRequest.CONTROL_AF_MODE_MACRO
                else -> CaptureRequest.CONTROL_AF_MODE_AUTO
            })

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (isoRange != null)
                req?.set(CaptureRequest.SENSOR_SENSITIVITY, isoRange.upper.coerceAtMost(800))

            val stabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            if (stabModes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true)
                req?.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)

            val bestFps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper == 30 }?.minByOrNull { it.lower }
                ?: chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxByOrNull { it.upper }
            if (bestFps != null)
                req?.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFps)

            cameraDevice?.createCaptureSession(listOf(surface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try { req?.let { session.setRepeatingRequest(it.build(), null, null) } }
                        catch (e: Exception) { Log.e(TAG, "Repeating request failed", e) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera session config failed")
                    }
                }, null)
        } catch (e: Exception) { Log.e(TAG, "startPreview failed", e) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Temple Input
    // ══════════════════════════════════════════════════════════════════════════

    private fun listenToTempleEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.Click -> handleTap()
                        is TempleAction.SlideForward  -> handleSwipe(increase = true)
                        is TempleAction.SlideBackward -> handleSwipe(increase = false)
                        is TempleAction.SlideUpwards -> handleVerticalSwipe(down = false)
                        is TempleAction.SlideDownwards -> handleVerticalSwipe(down = true)
                        is TempleAction.DoubleClick   -> finish()
                        else -> {}
                    }
                }
            }
        }
    }

    private fun handleTap() {
        val isOcrCurrently = currentMode == Mode.OCR_OFFLINE || currentMode == Mode.OCR_ONLINE
        if (isOcrCurrently && ocrSubState == OcrSubState.FROZEN) {
            // In frozen state, a tap dismisses the result and goes back to ready
            resetToReadyState()
            return
        }

        val prevMode = currentMode
        val entries  = Mode.entries
        currentMode  = entries[(currentMode.ordinal + 1) % entries.size]

        val isOcrNew = currentMode == Mode.OCR_OFFLINE || currentMode == Mode.OCR_ONLINE
        val isOcrPrev = prevMode == Mode.OCR_OFFLINE || prevMode == Mode.OCR_ONLINE

        when {
            isOcrNew && !isOcrPrev -> {
                ocrSubState = OcrSubState.SCANNING
                enterOcrLayout()
            }
            !isOcrNew && isOcrPrev -> {
                ocrSubState = OcrSubState.SCANNING
                clearOcrPanel()
                exitOcrLayout()
            }
            isOcrNew && isOcrPrev -> {
                // switched from offline to online, or online to offline
                ocrSubState = OcrSubState.SCANNING
                clearOcrPanel()
                enterOcrLayout()
            }
        }
        updateUI()
    }

    private fun handleSwipe(increase: Boolean) {
        when (currentMode) {
            Mode.ZOOM -> {
                renderer.zoom = (renderer.zoom + 0.2f * if (increase) 1 else -1).coerceIn(1.0f, 10.0f)
                updateUI()
            }
            Mode.FILTER -> {
                val dir = if (increase) 1 else -1
                renderer.currentFilter = (renderer.currentFilter + dir + filterNames.size) % filterNames.size
                updateUI()
            }
            Mode.BRIGHTNESS -> {
                renderer.brightness = (renderer.brightness + 0.1f * if (increase) 1 else -1).coerceIn(-0.5f, 0.5f)
                updateUI()
            }
            Mode.OCR_OFFLINE, Mode.OCR_ONLINE -> {
                if (ocrSubState == OcrSubState.SCANNING) {
                    // When scanning, a forward swipe takes the picture.
                    if (increase) {
                        triggerFreezeCapture()
                    }
                } else if (ocrSubState == OcrSubState.FROZEN) {
                    // When reading the frozen text, swipe forward increases font size,
                    // and swipe backward decreases font size.
                    val step = if (increase) OCR_FONT_STEP else -OCR_FONT_STEP
                    ocrFontSize = (ocrFontSize + step).coerceIn(OCR_FONT_MIN, OCR_FONT_MAX)
                    
                    runOnUiThread {
                        mBindingPair.updateView {
                            ocrTextView?.textSize   = ocrFontSize
                            ocrFontSizeBadge?.text  = "${ocrFontSize.toInt()}sp"
                        }
                    }
                }
            }
        }
    }

    private fun handleVerticalSwipe(down: Boolean) {
        // Vertical swipes are specifically for scrolling the OCR text when frozen
        if (currentMode == Mode.OCR_OFFLINE || currentMode == Mode.OCR_ONLINE) {
            if (ocrSubState == OcrSubState.FROZEN) {
                runOnUiThread {
                    mBindingPair.updateView {
                        // Scroll down if swiping down, up if swiping up. 250px is roughly half the text area height
                        val scrollAmount = if (down) 250 else -250
                        ocrScrollView?.smoothScrollBy(0, scrollAmount)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HUD Update
    // ══════════════════════════════════════════════════════════════════════════

    private fun updateUI() {
        runOnUiThread {
            mBindingPair.updateView {
                val modeDisplay = when (currentMode) {
                    Mode.OCR_OFFLINE -> "OCR (OFFLINE)"
                    Mode.OCR_ONLINE -> "OCR (ONLINE)"
                    else -> currentMode.name
                }
                tvMode.text = getString(R.string.mode_format, modeDisplay)
                
                when (currentMode) {
                    Mode.ZOOM -> {
                        tvValue.visibility     = View.VISIBLE
                        progressBar.visibility = View.VISIBLE
                        instructionText?.visibility = View.VISIBLE
                        ocrHintText?.visibility     = View.GONE
                        tvValue.text = String.format(Locale.US,
                            getString(R.string.zoom_value_format), renderer.zoom)
                        progressBar.progress = ((renderer.zoom - 1f) / 9f * 100).toInt()
                    }
                    Mode.FILTER -> {
                        tvValue.visibility     = View.VISIBLE
                        progressBar.visibility = View.VISIBLE
                        instructionText?.visibility = View.VISIBLE
                        ocrHintText?.visibility     = View.GONE
                        tvValue.text = filterNames[renderer.currentFilter]
                        progressBar.progress = 0
                    }
                    Mode.BRIGHTNESS -> {
                        tvValue.visibility     = View.VISIBLE
                        progressBar.visibility = View.VISIBLE
                        instructionText?.visibility = View.VISIBLE
                        ocrHintText?.visibility     = View.GONE
                        val pct = ((renderer.brightness + 0.5f) * 100).toInt()
                        tvValue.text = getString(R.string.percentage_format, pct)
                        progressBar.progress = pct
                    }
                    Mode.OCR_OFFLINE, Mode.OCR_ONLINE -> {
                        tvValue.visibility     = View.GONE
                        progressBar.visibility = View.GONE
                        instructionText?.visibility = View.GONE
                        ocrHintText?.visibility     = View.VISIBLE
                        ocrFontSizeBadge?.text = "${ocrFontSize.toInt()}sp"
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Utility
    // ══════════════════════════════════════════════════════════════════════════

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) startCamera()
    }
}