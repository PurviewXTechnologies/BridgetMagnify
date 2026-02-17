package com.siva.magnifyapp

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.siva.magnifyapp.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    private lateinit var renderer: CameraGLRenderer
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private enum class Mode { ZOOM, FILTER, BRIGHTNESS }
    private var currentMode = Mode.ZOOM
    private val filterNames = listOf("Normal", "High Contrast", "Inverted", "Amber", "Green Edge")
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Setup Dual Eye Display (Mirroring)
        configureDualEyeDisplay()

        // 2. Setup Renderer & Camera
        renderer = CameraGLRenderer {
            // This callback runs when the GL View is ready
            if (allPermissionsGranted()) startCamera()
            else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Attach renderer to our "Compatible" View
        mBindingPair.right.glTextureView.setRenderer(renderer)

        // 3. Setup Controls
        listenToTempleEvents()
        updateUI()
    }
    private fun configureDualEyeDisplay() {
        runOnUiThread {
            // LEFT EYE: Show Mirror
            mBindingPair.left.apply {
                glTextureView.visibility = View.GONE
                overlayContainer.visibility = View.GONE
                mirrorView.visibility = View.VISIBLE
            }

            // RIGHT EYE: Show Camera (TextureView) + UI
            mBindingPair.right.apply {
                glTextureView.visibility = View.VISIBLE
                overlayContainer.visibility = View.VISIBLE
                mirrorView.visibility = View.GONE
            }

            // LINK: This works now because glTextureView is a TextureView!
            try {
                mBindingPair.left.mirrorView.setSource(mBindingPair.right.root)
                mBindingPair.left.mirrorView.startMirroring()
            } catch (e: Exception) {
                Log.e("MagnifyApp", "Mirror Error", e)
            }
        }
    }
    private fun startCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            // Find Back Camera
            var selectedCameraId: String? = null
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    selectedCameraId = id
                    break
                }
            }
            val finalId = selectedCameraId ?: manager.cameraIdList[0]

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            manager.openCamera(finalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice?.close(); cameraDevice = null
                }
            }, null)
        } catch (e: Exception) { e.printStackTrace() }
    }
    private fun startPreview() {
        try {
            val texture = renderer.surfaceTexture ?: return
            texture.setDefaultBufferSize(1920, 1080)
            val surface = Surface(texture)

            val requestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder?.addTarget(surface)
            requestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        requestBuilder?.let { session.setRepeatingRequest(it.build(), null, null) }
                    } catch (e: Exception) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
        } catch (e: Exception) {}
    }
    private fun listenToTempleEvents() {
        lifecycleScope.launch {
            templeActionViewModel.state.collect { action ->
                when (action) {
                    is TempleAction.Click -> {
                        currentMode = Mode.values()[(currentMode.ordinal + 1) % Mode.values().size]
                        updateUI()
                    }
                    is TempleAction.SlideForward -> adjustValue(true)
                    is TempleAction.SlideBackward -> adjustValue(false)
                    is TempleAction.DoubleClick -> {
                        renderer.zoom = 1.0f; renderer.currentFilter = 0; renderer.brightness = 0.0f
                        updateUI()
                    }
                    else -> {}
                }
            }
        }
    }
    private fun adjustValue(increase: Boolean) {
        val dir = if (increase) 1 else -1
        when (currentMode) {
            Mode.ZOOM -> { renderer.zoom = (renderer.zoom + 0.2f * dir).coerceIn(1.0f, 10.0f) }
            Mode.FILTER -> {
                if (increase) renderer.currentFilter = (renderer.currentFilter + 1) % filterNames.size
                else { renderer.currentFilter--; if (renderer.currentFilter < 0) renderer.currentFilter = filterNames.size - 1 }
            }
            Mode.BRIGHTNESS -> { renderer.brightness = (renderer.brightness + 0.1f * dir).coerceIn(-0.5f, 0.5f) }
        }
        updateUI()
    }
    private fun updateUI() {
        runOnUiThread {
            mBindingPair.updateView {
                tvMode.text = "MODE: ${currentMode.name}"
                when (currentMode) {
                    Mode.ZOOM -> {
                        tvValue.text = String.format("%.1fx", renderer.zoom)
                        progressBar.progress = ((renderer.zoom - 1f) / 9f * 100).toInt()
                    }
                    Mode.FILTER -> {
                        tvValue.text = filterNames[renderer.currentFilter]
                        progressBar.progress = 0
                    }
                    Mode.BRIGHTNESS -> {
                        tvValue.text = "${((renderer.brightness + 0.5f) * 100).toInt()}%"
                        progressBar.progress = ((renderer.brightness + 0.5f) * 100).toInt()
                    }
                }
            }
        }
    }
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) startCamera()
    }

    override fun onPause() {
        try {
            captureSession?.close()
            cameraDevice?.close()
            mBindingPair.right.glTextureView.onPause()
        } catch (e: Exception) {}
        super.onPause()
    }
}