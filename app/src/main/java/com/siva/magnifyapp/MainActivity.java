package com.siva.magnifyapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES20;
import android.opengl.GLES11Ext;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends BaseVoiceActivity {
    private static final int REQUEST_CAMERA_PERMISSION = 100;

    private static final String PREFS = "MagnifyPrefs";
    private static final String KEY_FILTER = "currentFilter";

    // Camera related
    private GLSurfaceView glSurfaceView;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private SurfaceTexture previewSurfaceTexture;
    private Surface previewSurface;
    private String cameraId;
    private boolean isCameraPermissionGranted = false;
    private boolean isGLSurfaceCreated = false;
    private CameraGLRenderer renderer;

    // UI Components
    private LinearLayout menuContainer;
    private ViewFlipper menuFlipper;
    private TextView menuTitle;
    private LinearLayout popupContainer;
    private TextView popupTitle;
    private ProgressBar progressBar;
    private TextView progressText;
    private LinearLayout filterPopup;
    private ViewFlipper filterFlipper;
    private Button backButton;
    private Animation inFromBottom, outToTop, inFromTop, outToBottom;

    // Menu system
    private List<MenuItem> mainMenuItems;
    private List<MenuItem> currentFilterItems;
    private int currentMenuIndex = 0;
    private int currentFilterIndex = 0;
    private boolean isInMainMenu = true;
    private boolean isInFilterMenu = false;
    private boolean isInProgressMode = false;
    private String currentProgressType = "";

    // Auto-hide menu functionality
    private Handler menuHideHandler;
    private Runnable menuHideRunnable;
    private static final int MENU_HIDE_DELAY = 5000; // 5 seconds

    // Values
    private float zoomLevel = 1.0f;
    private float brightness = 0.0f;
    private float contrast = 1.0f;
    private float sharpness = 0.0f;
    private float appBrightness = 1.0f;
    private int currentFilter = 0;

    // Constants
    private static final float ZOOM_STEP = 0.25f;
    private static final float MAX_ZOOM = 10.0f;
    private static final float MIN_ZOOM = 1.0f;

    // Colors
    private int colorNormal, colorRed, colorAmber, colorGray, colorBlue;

    // Menu Item Class
    private static class MenuItem {
        String name;
        int type; // 0=filter, 1=zoom, 2=brightness, 3=contrast, 4=sharpness, 5=app_brightness

        MenuItem(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeColors();
        initializeCamera();
        initializeGL();
        initializeUI();
        initializeAnimations();

        int saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_FILTER, 0);
        currentFilter = saved;
        renderer.setFilterMode(saved);
        updateUIColors();

        setupMainMenu();
        initializeMenuAutoHide();
        requestCameraPermission();
    }

    private void initializeColors() {
        colorNormal = ContextCompat.getColor(this, R.color.filter_normal);
        colorRed = ContextCompat.getColor(this, R.color.filter_red);
        colorAmber = ContextCompat.getColor(this, R.color.filter_amber);
        colorGray = ContextCompat.getColor(this, R.color.filter_gray);
        colorBlue = ContextCompat.getColor(this, R.color.filter_blue);
    }

    private void initializeCamera() {
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void initializeGL() {
        glSurfaceView = findViewById(R.id.glSurfaceView);
        renderer = new CameraGLRenderer();
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    private void initializeUI() {
        menuContainer = findViewById(R.id.menuContainer);
        menuFlipper = findViewById(R.id.menuFlipper);
        menuTitle = findViewById(R.id.menuTitle);
        popupContainer = findViewById(R.id.popupContainer);
        popupTitle = findViewById(R.id.popupTitle);
        progressBar = findViewById(R.id.progressBar);
        progressText = findViewById(R.id.progressText);
        filterPopup = findViewById(R.id.filterPopup);
        filterFlipper = findViewById(R.id.filterFlipper);
        backButton = findViewById(R.id.backButton);

        backButton.setOnClickListener(v -> handleBackAction());

        Button filterBack = findViewById(R.id.filterBackButton);
        filterBack.setOnClickListener(v -> handleBackAction());

        popupContainer.setVisibility(View.GONE);
        filterPopup.setVisibility(View.GONE);
    }

    private void initializeAnimations() {
        inFromBottom = AnimationUtils.loadAnimation(this, R.anim.slide_in_from_bottom);
        outToTop = AnimationUtils.loadAnimation(this, R.anim.slide_out_to_top);
        inFromTop = AnimationUtils.loadAnimation(this, R.anim.slide_in_from_top);
        outToBottom = AnimationUtils.loadAnimation(this, R.anim.slide_out_to_bottom);
    }

    private void initializeMenuAutoHide() {
        menuHideHandler = new Handler(Looper.getMainLooper());
        menuHideRunnable = new Runnable() {
            @Override
            public void run() {
                hideMenu();
            }
        };
        scheduleMenuHide();
    }

    private void scheduleMenuHide() {
        menuHideHandler.removeCallbacks(menuHideRunnable);
        if (isInMainMenu && !isInFilterMenu && !isInProgressMode) {
            menuHideHandler.postDelayed(menuHideRunnable, MENU_HIDE_DELAY);
        }
    }

    private void showMenu() {
        if (isInMainMenu && menuContainer.getVisibility() != View.VISIBLE) {
            menuContainer.setVisibility(View.VISIBLE);
            scheduleMenuHide();
        }
    }

    private void hideMenu() {
        if (isInMainMenu && !isInFilterMenu && !isInProgressMode) {
            menuContainer.setVisibility(View.GONE);
        }
    }

    private void onUserActivity() {
        showMenu();
    }

    private void setupMainMenu() {
        mainMenuItems = new ArrayList<>();
        mainMenuItems.add(new MenuItem("Filters", 0));
        mainMenuItems.add(new MenuItem("Zoom", 1));
        mainMenuItems.add(new MenuItem("Brightness", 2));
        mainMenuItems.add(new MenuItem("Contrast", 3));
        mainMenuItems.add(new MenuItem("Sharpness", 4));
        mainMenuItems.add(new MenuItem("App Brightness", 5));

        currentFilterItems = new ArrayList<>();
        currentFilterItems.add(new MenuItem("Normal", 0));
        currentFilterItems.add(new MenuItem("Amber", 1));
        currentFilterItems.add(new MenuItem("Grayscale", 2));

        ((TextView) menuFlipper.getChildAt(0)).setText(mainMenuItems.get(0).name);
        menuFlipper.setDisplayedChild(0);
        currentMenuIndex = 0;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        onUserActivity();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_VOLUME_UP:
                handleScrollUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                handleScrollDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                handleSelectAction();
                return true;
            case KeyEvent.KEYCODE_BACK:
                handleBackAction();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleScrollUp() {
        if (isInProgressMode) {
            adjustProgressValue(true);
        } else if (isInFilterMenu) {
            int currentChild = filterFlipper.getDisplayedChild();
            int prevChild = (currentChild - 1 + 2) % 2;
            TextView prevView = (TextView) filterFlipper.getChildAt(prevChild);
            int prevIndex = (currentFilterIndex - 1 + currentFilterItems.size()) % currentFilterItems.size();
            prevView.setText(currentFilterItems.get(prevIndex).name);
            filterFlipper.setInAnimation(inFromTop);
            filterFlipper.setOutAnimation(outToBottom);
            filterFlipper.showPrevious();
            currentFilterIndex = prevIndex;
            currentFilter = currentFilterIndex;
            applyFilterToRenderer(currentFilter);
            updateUIColors();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FILTER, currentFilter).apply();
        } else if (isInMainMenu) {
            int currentChild = menuFlipper.getDisplayedChild();
            int prevChild = (currentChild - 1 + 2) % 2;
            TextView prevView = (TextView) menuFlipper.getChildAt(prevChild);
            int prevIndex = (currentMenuIndex - 1 + mainMenuItems.size()) % mainMenuItems.size();
            prevView.setText(mainMenuItems.get(prevIndex).name);
            menuFlipper.setInAnimation(inFromTop);
            menuFlipper.setOutAnimation(outToBottom);
            menuFlipper.showPrevious();
            currentMenuIndex = prevIndex;
        }
    }

    private void handleScrollDown() {
        if (isInProgressMode) {
            adjustProgressValue(false);
        } else if (isInFilterMenu) {
            int currentChild = filterFlipper.getDisplayedChild();
            int nextChild = (currentChild + 1) % 2;
            TextView nextView = (TextView) filterFlipper.getChildAt(nextChild);
            int nextIndex = (currentFilterIndex + 1) % currentFilterItems.size();
            nextView.setText(currentFilterItems.get(nextIndex).name);
            filterFlipper.setInAnimation(inFromBottom);
            filterFlipper.setOutAnimation(outToTop);
            filterFlipper.showNext();
            currentFilterIndex = nextIndex;
            currentFilter = currentFilterIndex;
            applyFilterToRenderer(currentFilter);
            updateUIColors();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FILTER, currentFilter).apply();
        } else if (isInMainMenu) {
            int currentChild = menuFlipper.getDisplayedChild();
            int nextChild = (currentChild + 1) % 2;
            TextView nextView = (TextView) menuFlipper.getChildAt(nextChild);
            int nextIndex = (currentMenuIndex + 1) % mainMenuItems.size();
            nextView.setText(mainMenuItems.get(nextIndex).name);
            menuFlipper.setInAnimation(inFromBottom);
            menuFlipper.setOutAnimation(outToTop);
            menuFlipper.showNext();
            currentMenuIndex = nextIndex;
        }
    }

    private void handleSelectAction() {
        if (isInProgressMode) {
            confirmProgressValue();
        } else if (isInFilterMenu) {
            selectFilter();
        } else if (isInMainMenu) {
            selectMainMenuItem();
        }
    }

    public void handleBackAction(View v) {
        handleBackAction();
    }

    private void handleBackAction() {
        if (isInProgressMode) {
            closeProgressMode();
        } else if (isInFilterMenu) {
            closeFilterMenu();
        }
    }

    private void selectMainMenuItem() {
        MenuItem selected = mainMenuItems.get(currentMenuIndex);
        if (selected.type == 0) {
            openFilterMenu();
        } else {
            openProgressMode(selected.name, selected.type);
        }
    }

    private void openFilterMenu() {
        isInMainMenu = false;
        isInFilterMenu = true;
        currentFilterIndex = currentFilter;
        menuHideHandler.removeCallbacks(menuHideRunnable);
        menuContainer.setVisibility(View.GONE);
        filterPopup.setVisibility(View.VISIBLE);
        ((TextView) filterFlipper.getChildAt(0)).setText(currentFilterItems.get(currentFilterIndex).name);
        ((TextView) filterFlipper.getChildAt(1)).setText(currentFilterItems.get(currentFilterIndex).name);
        filterFlipper.setDisplayedChild(0);
    }

    private void closeFilterMenu() {
        isInFilterMenu = false;
        filterPopup.setVisibility(View.GONE);
        isInMainMenu = true;
        menuContainer.setVisibility(View.VISIBLE);
        scheduleMenuHide();
    }

    private void selectFilter() {
        currentFilter = currentFilterIndex;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_FILTER, currentFilter)
                .apply();
        applyFilterToRenderer(currentFilter);
        updateUIColors();
        closeFilterMenu();
        Toast.makeText(this, "Filter applied: " + currentFilterItems.get(currentFilterIndex).name, Toast.LENGTH_SHORT).show();
    }

    private void applyFilterToRenderer(int filterMode) {
        renderer.setFilterMode(filterMode);
    }

    private void openProgressMode(String title, int type) {
        isInMainMenu = false;
        isInProgressMode = true;
        currentProgressType = title;
        menuHideHandler.removeCallbacks(menuHideRunnable);
        menuContainer.setVisibility(View.GONE);
        popupContainer.setVisibility(View.VISIBLE);
        popupTitle.setText(title);
        int progress = getCurrentProgress(type);
        progressBar.setProgress(progress);
        updateProgressText(progress, type);
    }

    private void closeProgressMode() {
        isInProgressMode = false;
        isInMainMenu = true;
        popupContainer.setVisibility(View.GONE);
        menuContainer.setVisibility(View.VISIBLE);
        scheduleMenuHide();
    }

    private void adjustProgressValue(boolean increase) {
        int currentProgress = progressBar.getProgress();
        int step = 5;
        if (increase) {
            currentProgress = Math.min(100, currentProgress + step);
        } else {
            currentProgress = Math.max(0, currentProgress - step);
        }
        progressBar.setProgress(currentProgress);
        int type = getTypeFromTitle(currentProgressType);
        updateProgressText(currentProgress, type);
        applyProgressValue(currentProgress, type);
    }

    private void confirmProgressValue() {
        int progress = progressBar.getProgress();
        int type = getTypeFromTitle(currentProgressType);
        applyProgressValue(progress, type);
        closeProgressMode();
        Toast.makeText(this, currentProgressType + " set to " + progress + "%", Toast.LENGTH_SHORT).show();
    }

    private int getCurrentProgress(int type) {
        switch (type) {
            case 1: return (int) ((zoomLevel - MIN_ZOOM) / (MAX_ZOOM - MIN_ZOOM) * 100);
            case 2: return (int) ((brightness + 1.0f) / 2.0f * 100);
            case 3: return (int) (contrast / 2.0f * 100);
            case 4: return (int) (sharpness * 100);
            case 5: return (int) (appBrightness * 100);
            default: return 50;
        }
    }

    private void updateProgressText(int progress, int type) {
        String text = progress + "%";
        switch (type) {
            case 1: float zoom = MIN_ZOOM + (MAX_ZOOM - MIN_ZOOM) * progress / 100f; text = String.format("%.1fx", zoom); break;
            case 2: case 3: case 4: case 5: text = progress + "%"; break;
        }
        progressText.setText(text);
    }

    private void applyProgressValue(int progress, int type) {
        switch (type) {
            case 1: zoomLevel = MIN_ZOOM + (MAX_ZOOM - MIN_ZOOM) * progress / 100f; renderer.setZoomLevel(zoomLevel); break;
            case 2: brightness = (progress / 50f) - 1.0f; renderer.setBrightness(brightness); break;
            case 3: contrast = progress / 50f; renderer.setContrast(contrast); break;
            case 4: sharpness = progress / 100f; renderer.setSharpness(sharpness); break;
            case 5: appBrightness = progress / 100f; setAppBrightness(appBrightness); break;
        }
        if (type != 5) glSurfaceView.requestRender();
    }

    private int getTypeFromTitle(String title) {
        switch (title) {
            case "Zoom": return 1;
            case "Brightness": return 2;
            case "Contrast": return 3;
            case "Sharpness": return 4;
            case "App Brightness": return 5;
            default: return 0;
        }
    }

    private void updateUIColors() {
        int uiColor;
        switch (currentFilter) {
            case 1: uiColor = colorAmber; break;
            case 2: uiColor = colorGray; break;
            case 0: default: uiColor = colorNormal; break;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(uiColor);
        }
        if (currentFilter != 0) {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setColor(uiColor);
            shape.setAlpha(200);
            shape.setCornerRadius(16);
            menuContainer.setBackground(shape);
        } else {
            menuContainer.setBackgroundResource(R.drawable.menu_background);
        }
    }

    private void setAppBrightness(float brightnessValue) {
        brightnessValue = Math.max(0f, Math.min(brightnessValue, 1f));
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = brightnessValue;
        getWindow().setAttributes(layoutParams);
    }

    @Override
    protected void setupVoiceCommands() {
        registerVoiceCommand("scroll up", () -> runOnUiThread(() -> {
            onUserActivity();
            handleScrollUp();
        }));
        registerVoiceCommand("scroll down", () -> runOnUiThread(() -> {
            onUserActivity();
            handleScrollDown();
        }));
        registerVoiceCommand("select", () -> runOnUiThread(() -> {
            onUserActivity();
            handleSelectAction();
        }));
        registerVoiceCommand("back", () -> runOnUiThread(() -> {
            onUserActivity();
            handleBackAction();
        }));
        registerVoiceCommand("normal", () -> runOnUiThread(() -> {
            onUserActivity();
            applyFilterDirectly(0);
        }));
        registerVoiceCommand("amber", () -> runOnUiThread(() -> {
            onUserActivity();
            applyFilterDirectly(1);
        }));
        registerVoiceCommand("grayscale", () -> runOnUiThread(() -> {
            onUserActivity();
            applyFilterDirectly(2);
        }));
        registerVoiceCommand("zoom in", () -> runOnUiThread(() -> {
            onUserActivity();
            zoomLevel = Math.min(MAX_ZOOM, zoomLevel + ZOOM_STEP);
            renderer.setZoomLevel(zoomLevel);
            glSurfaceView.requestRender();
        }));
        registerVoiceCommand("zoom out", () -> runOnUiThread(() -> {
            onUserActivity();
            zoomLevel = Math.max(MIN_ZOOM, zoomLevel - ZOOM_STEP);
            renderer.setZoomLevel(zoomLevel);
            glSurfaceView.requestRender();
        }));
    }

    private void applyFilterDirectly(int filterIndex) {
        if (filterIndex < 0 || filterIndex >= currentFilterItems.size()) return;
        currentFilter = filterIndex;
        applyFilterToRenderer(currentFilter);
        updateUIColors();
        Toast.makeText(this, "Filter applied: " + currentFilterItems.get(filterIndex).name, Toast.LENGTH_SHORT).show();
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            isCameraPermissionGranted = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                isCameraPermissionGranted = true;
                if (isGLSurfaceCreated) openCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to use the magnifier", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();
        onUserActivity();
    }

    @Override
    protected void onPause() {
        closeCamera();
        glSurfaceView.onPause();
        menuHideHandler.removeCallbacks(menuHideRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (menuHideHandler != null) {
            menuHideHandler.removeCallbacks(menuHideRunnable);
        }
        super.onDestroy();
    }

    private void openCamera() {
        if (!isCameraPermissionGranted || cameraId == null) return;
        if (previewSurfaceTexture == null) {
            Log.w("MainActivity", "SurfaceTexture not ready for camera");
            return;
        }
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            Size optimalSize = getBestPreviewSize(previewSizes, glSurfaceView.getWidth(), glSurfaceView.getHeight());
            previewSurfaceTexture.setDefaultBufferSize(optimalSize.getWidth(), optimalSize.getHeight());
            previewSurface = new Surface(previewSurfaceTexture);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    try {
                        CaptureRequest.Builder reqBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        reqBuilder.addTarget(previewSurface);
                        reqBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        reqBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        reqBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                        cameraDevice.createCaptureSession(
                                java.util.Collections.singletonList(previewSurface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        captureSession = session;
                                        try {
                                            captureSession.setRepeatingRequest(reqBuilder.build(), null, null);
                                        } catch (CameraAccessException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        Toast.makeText(MainActivity.this, "Failed to start camera preview!", Toast.LENGTH_SHORT).show();
                                    }
                                }, null
                        );
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(CameraDevice device, int error) {
                    Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_SHORT).show();
                    device.close();
                    cameraDevice = null;
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private Size getBestPreviewSize(Size[] sizes, int targetWidth, int targetHeight) {
        float targetRatio = (float) targetWidth / targetHeight;
        Size bestSize = null;
        for (Size size : sizes) {
            float ratio = (float) size.getWidth() / size.getHeight();
            if (Math.abs(ratio - targetRatio) < 0.1f) {
                if (bestSize == null || size.getWidth() > bestSize.getWidth()) {
                    bestSize = size;
                }
            }
        }
        if (bestSize == null) {
            bestSize = sizes[0];
            for (Size s : sizes) {
                if (s.getWidth() > bestSize.getWidth()) {
                    bestSize = s;
                }
            }
        }
        return bestSize;
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private class CameraGLRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        private float zoom = 1.0f;
        private float brightness = 0.0f;
        private float contrast = 1.0f;
        private float sharpness = 0.0f;
        public volatile int currentFilter = 0;

        private final String VERTEX_SHADER =
                "#version 100\n" +
                        "attribute vec4 aPosition;\n" +
                        "attribute vec2 aTexCoord;\n" +
                        "uniform mat4 uTexMatrix;\n" +
                        "uniform float uZoom;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "    gl_Position = aPosition;\n" +
                        "    vec2 zoomedCoord = (aTexCoord - 0.5) / uZoom + 0.5;\n" +
                        "    vTexCoord = (uTexMatrix * vec4(zoomedCoord, 0.0, 1.0)).xy;\n" +
                        "}\n";

        private final String FRAGMENT_SHADER =
                "#version 100\n" +
                        "#extension GL_OES_EGL_image_external : require\n" +
                        "precision highp float;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "uniform int uFilterMode;\n" +
                        "uniform float uBrightness;\n" +
                        "uniform float uContrast;\n" +
                        "uniform float uSharpness;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "    vec4 color = texture2D(sTexture, vTexCoord);\n" +
                        "    if (uFilterMode == 1) {\n" +
                        "        color = vec4(color.r, color.g * 0.7, 0.0, 1.0);\n" +
                        "    } else if (uFilterMode == 2) {\n" +
                        "        float gray = color.r * 0.299 + color.g * 0.587 + color.b * 0.114;\n" +
                        "        color = vec4(gray, gray, gray, 1.0);\n" +
                        "    }\n" +
                        "    color.rgb = (color.rgb - 0.5) * uContrast + 0.5;\n" +
                        "    color.rgb += uBrightness;\n" +
                        "    color.rgb = clamp(color.rgb, 0.0, 1.0);\n" +
                        "    if (uSharpness > 0.0) {\n" +
                        "        vec4 blurred = texture2D(sTexture, vTexCoord + vec2(0.01, 0.0)) * 0.25 +\n" +
                        "                       texture2D(sTexture, vTexCoord + vec2(-0.01, 0.0)) * 0.25 +\n" +
                        "                       texture2D(sTexture, vTexCoord + vec2(0.0, 0.01)) * 0.25 +\n" +
                        "                       texture2D(sTexture, vTexCoord + vec2(0.0, -0.01)) * 0.25;\n" +
                        "        color.rgb += (color.rgb - blurred.rgb) * uSharpness;\n" +
                        "    }\n" +
                        "    gl_FragColor = color;\n" +
                        "}\n";

        private final float[] QUAD_COORDS = { -1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f };
        private final float[] TEX_COORDS = { 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f };
        private FloatBuffer vertexBuffer, texBuffer;
        private int program;
        private int positionHandle, texCoordHandle, texMatrixHandle, filterModeHandle, zoomHandle;
        private int brightnessHandle, contrastHandle, sharpnessHandle;
        private int cameraTextureId;
        private final float[] texMatrix = new float[16];

        CameraGLRenderer() {
            vertexBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(QUAD_COORDS).position(0);
            texBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            texBuffer.put(TEX_COORDS).position(0);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            cameraTextureId = tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            previewSurfaceTexture = new SurfaceTexture(cameraTextureId);
            previewSurfaceTexture.setOnFrameAvailableListener(this, new Handler(Looper.getMainLooper()));

            int vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);

            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
            texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
            filterModeHandle = GLES20.glGetUniformLocation(program, "uFilterMode");
            zoomHandle = GLES20.glGetUniformLocation(program, "uZoom");
            brightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness");
            contrastHandle = GLES20.glGetUniformLocation(program, "uContrast");
            sharpnessHandle = GLES20.glGetUniformLocation(program, "uSharpness");

            isGLSurfaceCreated = true;
            runOnUiThread(() -> { if (isCameraPermissionGranted) openCamera(); });
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (previewSurfaceTexture != null) {
                previewSurfaceTexture.updateTexImage();
                previewSurfaceTexture.getTransformMatrix(texMatrix);
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
            GLES20.glUniform1i(filterModeHandle, currentFilter);
            GLES20.glUniform1f(zoomHandle, zoom);
            GLES20.glUniform1f(brightnessHandle, brightness);
            GLES20.glUniform1f(contrastHandle, contrast);
            GLES20.glUniform1f(sharpnessHandle, sharpness);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            glSurfaceView.requestRender();
        }

        public void setFilterMode(int mode) {
            if (currentFilter != mode) {
                currentFilter = mode;
                glSurfaceView.requestRender();
                new Handler(Looper.getMainLooper()).postDelayed(() -> glSurfaceView.requestRender(), 100);
            }
        }

        public void setZoomLevel(float zoomLevel) {
            this.zoom = zoomLevel;
        }

        public void setBrightness(float brightness) {
            this.brightness = brightness;
        }

        public void setContrast(float contrast) {
            this.contrast = contrast;
        }

        public void setSharpness(float sharpness) {
            this.sharpness = sharpness;
        }

        private int loadShader(int type, String code) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, code);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e("CameraGLRenderer", "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }
    }
}