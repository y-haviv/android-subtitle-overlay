package com.example.subtitles;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import com.example.subtitles.model.audio.AudioCaptureService;
import com.example.subtitles.util.AssetUtils;
import com.example.subtitles.view.overlay.FloatingToggleButtonService;
import com.example.subtitles.view.overlay.SubtitleOverlayService;
import com.example.subtitles.view_model.MainPipeline;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity is the central activity of the application.
 *
 * Responsibilities:
 * 1. Handles user interactions to start/stop subtitles.
 * 2. Manages permissions for audio recording, overlay, and screen capture.
 * 3. Initializes and manages the MainPipeline for transcription and translation.
 * 4. Coordinates the overlay services (subtitle display and floating toggle button).
 * 5. Listens to broadcasts from background services to react when ready.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    /// Request codes
    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final int SYSTEM_ALERT_WINDOW_PERMISSION = 5678;

    /// System service for screen capture
    private MediaProjectionManager projectionManager;

    /// Core pipeline for audio capture, transcription, and translation
    private MainPipeline pipeline;

    /// Main UI toggle button to start/stop subtitles
    private ImageButton mainButton;

    /// Tracks whether secondary button/overlay is displayed
    private boolean showSec = false;

    /// Tracks whether transcription pipeline is running
    private boolean running = false;

    /// Store MediaProjection permission result data
    private Intent projectionData = null;
    private int projectionResultCode = -1;

    /// Activity Result Launchers for permissions
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<Intent> screenCaptureLauncher;

    /// Indicates if background services are initialized and ready
    private boolean serviceIsReady = false;

    /// Tracks if FloatingToggleButtonService listener has been registered
    private boolean secListenerRegistered = false;

    /// Tracks whether startup was requested before RECORD_AUDIO was granted
    private boolean pendingStartAfterAudioPermission = false;

    /// Synchronization lock for UI and service calls
    private final Object lock = new Object();

    /// Non-cancelable loading dialog while required runtime models are downloaded
    private AlertDialog modelDownloadDialog;

    /**
     * BroadcastReceiver to listen for ACTION_SERVICE_READY
     * This is sent when the background audio capture or overlay service is ready.
     */
    private final BroadcastReceiver serviceReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.subtitles.ACTION_SERVICE_READY".equals(intent.getAction())) {
                Log.d(TAG, "Received ACTION_SERVICE_READY broadcast");
                serviceIsReady = true;
                logUiState("serviceReadyReceiver:onReceive");

                runOnUiThread(() -> {
                    // If overlay and screen capture permissions are granted and secondary button not shown, start it
                    if (!showSec && hasProjectionPermission() && Settings.canDrawOverlays(MainActivity.this)) {
                        Log.d(TAG, "Service ready and permissions OK, starting transcription");
                        synchronized (lock) {
                            startSecoundryButton(projectionResultCode, projectionData);
                        }
                    } else {
                        // Otherwise, reset the main button UI
                        Log.d(TAG, "Service ready received but UI preconditions were not met, resetting button");
                        resetTranscriptionButton();
                    }
                });
            }
        }
    };

    /**
     * BroadcastReceiver to listen for FloatingToggleButtonService readiness
     * Registers the on/off listener for user interaction with floating button.
     */
    private final BroadcastReceiver toggleServiceReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.subtitles.ACTION_TOGGLE_SERVICE_READY".equals(intent.getAction())) {
                Log.d(TAG, "Received ACTION_TOGGLE_SERVICE_READY broadcast");
                if (!secListenerRegistered) {
                    // Register listener to respond when user toggles the floating button
                    FloatingToggleButtonService.registerListener(isOn -> {
                        Log.d(TAG, "Floating toggle changed: isOn=" + isOn);
                        runOnUiThread(() -> {
                            if (isOn) {
                                synchronized (lock) {
                                    startTranscription(projectionResultCode, projectionData);
                                }
                            }
                            else {
                                synchronized (lock) {
                                    stopTranscription();
                                }
                            }
                        });
                    });
                    secListenerRegistered = true;
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Initialize UI components
        mainButton = findViewById(R.id.toggleMainButton);
        // Initialize MediaProjectionManager for screen capture requests
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // Register broadcast receivers
        IntentFilter filter = new IntentFilter("com.example.subtitles.ACTION_SERVICE_READY");
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceReadyReceiver, filter);

        LocalBroadcastManager.getInstance(this).registerReceiver(toggleServiceReadyReceiver,
                new IntentFilter("com.example.subtitles.ACTION_TOGGLE_SERVICE_READY"));

        // Request audio recording permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
        }

        // Initialize the MainPipeline in a background thread
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            try {
                long t0 = System.currentTimeMillis();
                MainPipeline bgPipeline = new MainPipeline(this);
                long dt = System.currentTimeMillis() - t0;
                Log.d(TAG, "Pipeline init took " + dt + "ms");
                runOnUiThread(() -> {
                    pipeline = bgPipeline;
                    mainButton.setEnabled(true); // Enable button after pipeline is ready
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to init pipeline: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish(); // Close activity if pipeline fails to initialize
                });
            }
        });

        // Register ActivityResultLauncher for overlay permission
        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "overlayPermissionLauncher result received, canDrawOverlays="
                            + Settings.canDrawOverlays(this));
                    if (Settings.canDrawOverlays(this)) {
                        requestScreenCapturePermission();
                    } else {
                        Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show();
                        resetTranscriptionButton();
                    }
                });


        // Register ActivityResultLauncher for screen capture permission
        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "ScreenCaptureLauncher result code: " + result.getResultCode() + ", data: " + result.getData());
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        projectionResultCode = result.getResultCode();
                        projectionData = result.getData();
                        Log.d(TAG, "Projection permission granted, saving data");

                        startAudioCaptureService("screenCaptureLauncher");

                    } else {
                        Log.d(TAG, "Projection permission denied or data null");
                        Toast.makeText(this, "Capture permission denied", Toast.LENGTH_SHORT).show();
                        resetTranscriptionButton();
                    }
                });
        // Setup main toggle button click listener
        mainButton.setImageResource(R.drawable.turn_on);
        mainButton.setOnClickListener(v -> {
            logUiState("mainButton:onClick:before");
            mainButton.setEnabled(false); // Disable until action completes
            if (!showSec) {
                beginSubtitleStartupFlow();
            } else {
                // Stop secondary button & transcription
                synchronized (lock) {
                    stopSecoundryButton();
                }
            }
        });
        // Settings button to open SettingsActivity
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, com.example.subtitles.view.screens.SettingsActivity.class);
            startActivity(intent);
        });

        // Ensure runtime AI models exist before pipeline initialization.
        startModelDownloadFlow();

    }

    /**
     * ---------------------------------------------------------------------
     * Starts runtime model availability workflow.
     * ---------------------------------------------------------------------
     *
     * UI flow:
     * 1. Show non-cancelable loading dialog.
     * 2. Ensure required models exist (download missing ones).
     * 3. Continue app initialization only after success.
     * 4. Show actionable error UI (Retry / Close) on failure.
     */
    private void startModelDownloadFlow() {
        showModelDownloadDialog();

        AssetUtils.ensureRuntimeModelsDownloaded(this, new AssetUtils.ModelDownloadCallback() {
            @Override
            public void onProgress(String modelName, int percentage) {
                updateModelDownloadMessage("Downloading " + modelName + " (" + percentage + "%)");
            }

            @Override
            public void onSuccess() {
                dismissModelDownloadDialog();
            }

            @Override
            public void onError(String errorMessage) {
                dismissModelDownloadDialog();
                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Model Download Failed")
                        .setMessage(errorMessage)
                        .setCancelable(false)
                        .setPositiveButton("Retry", (dialog, which) -> startModelDownloadFlow())
                        .setNegativeButton("Close", (dialog, which) -> finish())
                        .show();
            }
        });
    }


    /**
     * Creates/shows non-cancelable progress dialog for model preparation.
     */
    private void showModelDownloadDialog() {
        if (modelDownloadDialog != null && modelDownloadDialog.isShowing()) {
            return;
        }
        modelDownloadDialog = new AlertDialog.Builder(this)
                .setTitle("Preparing AI Models")
                .setMessage("Checking model files...")
                .setCancelable(false)
                .create();
        modelDownloadDialog.show();
    }

    /**
     * Updates message text of the model download dialog.
     *
     * @param message UI status text (model name + percentage)
     */
    private void updateModelDownloadMessage(String message) {
        if (modelDownloadDialog != null && modelDownloadDialog.isShowing()) {
            modelDownloadDialog.setMessage(message);
        }
    }

    /**
     * Safely dismisses model progress dialog if currently visible.
     */
    private void dismissModelDownloadDialog() {
        if (modelDownloadDialog != null && modelDownloadDialog.isShowing()) {
            modelDownloadDialog.dismiss();
        }
    }

    /**
     * Resets the main button UI (icon and enabled state)
     */
    private void resetTranscriptionButton() {
        Log.d(TAG, "resetTranscriptionButton()");
        mainButton.setEnabled(true);
        mainButton.setImageResource(R.drawable.turn_on);
        logUiState("resetTranscriptionButton:after");
    }

    /**
     * Logs the relevant UI and permission state for debugging startup transitions.
     */
    private void logUiState(String source) {
        Log.d(TAG, source
                + " | showSec=" + showSec
                + ", running=" + running
                + ", serviceIsReady=" + serviceIsReady
                + ", hasProjectionPermission=" + hasProjectionPermission()
                + ", canDrawOverlays=" + Settings.canDrawOverlays(this)
                + ", mainButtonEnabled=" + (mainButton != null && mainButton.isEnabled())
                + ", pendingStartAfterAudioPermission=" + pendingStartAfterAudioPermission);
    }

    /**
     * Handles the main-button start flow across runtime permissions and service readiness.
     */
    private void beginSubtitleStartupFlow() {
        logUiState("beginSubtitleStartupFlow:entered");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterAudioPermission = true;
            Log.d(TAG, "RECORD_AUDIO missing, requesting permission and deferring startup");
            Toast.makeText(this,
                    "Please grant audio permission first",
                    Toast.LENGTH_SHORT).show();
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
            return;
        }

        if (!hasProjectionPermission() || !Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Projection or overlay permission missing, starting permission flow");
            startProjectionRequest();
            return;
        }

        if (!serviceIsReady) {
            Log.d(TAG, "Permissions are ready but AudioCaptureService is not ready yet, starting service");
            startAudioCaptureService("beginSubtitleStartupFlow");
            return;
        }

        synchronized (lock) {
            startSecoundryButton(projectionResultCode, projectionData);
        }
    }

    /**
     * Starts overlay permission request flow and/or screen capture request
     */
    private void startProjectionRequest() {
        Log.d(TAG, "startProjectionRequest()");
        logUiState("startProjectionRequest:entered");
        Toast.makeText(this, "Please grant overlay permission to start subtitles", Toast.LENGTH_SHORT).show();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission missing, launching settings screen");
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                overlayPermissionLauncher.launch(intent);
                return;
            }

            Log.d(TAG, "Overlay permission already granted, requesting screen capture");
            requestScreenCapturePermission();
        } else {
            Toast.makeText(this, "Requires Android 10+", Toast.LENGTH_SHORT).show();
            resetTranscriptionButton();
        }
    }
    /**
     * Checks if we have valid screen capture permission
     */
    private boolean hasProjectionPermission() {
        return projectionData != null && projectionResultCode == Activity.RESULT_OK;
    }
    /**
     * Requests screen capture permission via MediaProjectionManager
     */
    private void requestScreenCapturePermission() {
        Log.d(TAG, "requestScreenCapturePermission()");
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        screenCaptureLauncher.launch(captureIntent);
    }

    /**
     * Starts the audio capture foreground service using the stored projection grant.
     */
    private void startAudioCaptureService(String reason) {
        if (!hasProjectionPermission()) {
            Log.w(TAG, "startAudioCaptureService called without valid projection permission, reason=" + reason);
            resetTranscriptionButton();
            return;
        }

        serviceIsReady = false;
        Intent serviceIntent = new Intent(this, AudioCaptureService.class);
        serviceIntent.putExtra("PROJECTION_CODE", projectionResultCode);
        serviceIntent.putExtra("PROJECTION_DATA", projectionData);

        Log.d(TAG, "Starting AudioCaptureService, reason=" + reason
                + ", projectionResultCode=" + projectionResultCode
                + ", projectionData=" + projectionData);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AudioCaptureService", e);
            Toast.makeText(this, "Failed to start audio capture service", Toast.LENGTH_SHORT).show();
            resetTranscriptionButton();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            Log.d(TAG, "onRequestPermissionsResult for RECORD_AUDIO, grantResultsLength=" + grantResults.length);
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted");
                mainButton.setEnabled(true);
                if (pendingStartAfterAudioPermission) {
                    Log.d(TAG, "Continuing deferred startup after RECORD_AUDIO grant");
                    pendingStartAfterAudioPermission = false;
                    beginSubtitleStartupFlow();
                } else {
                    logUiState("onRequestPermissionsResult:audioGrantedNoDeferredStart");
                }
            } else {
                pendingStartAfterAudioPermission = false;
                Toast.makeText(this,
                        "Audio capture permission is required for subtitles",
                        Toast.LENGTH_LONG).show();
                resetTranscriptionButton();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        logUiState("onResume:entered");
        if(pipeline!=null) {
            pipeline.setParmeters();
        }
        // Stop transcription if overlay permission revoked
        if (!Settings.canDrawOverlays(this)) {
            if (showSec) {
                stopSecoundryButton();
            }
            resetTranscriptionButton();
            Toast.makeText(this, "Overlay permission revoked – transcription stopped", Toast.LENGTH_SHORT).show();
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
    }


    /**
     * Initializes and displays the secondary floating toggle button.
     * Sets up listener for user to start/stop transcription.
     */
    private void startSecoundryButton(int resultCode, Intent data) {
        Log.d(TAG, "startSecoundryButton called with resultCode=" + resultCode + ", data=" + data);
        if (showSec) {
            Log.d(TAG, "startSecoundryButton ignored because showSec is already true");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "startSecoundryButton aborted: overlay permission missing");
            Toast.makeText(this, "Overlay permission missing", Toast.LENGTH_SHORT).show();
            resetTranscriptionButton();
            return;
        }

        if (!hasProjectionPermission()) {
            Log.w(TAG, "startSecoundryButton aborted: screen capture permission missing");
            Toast.makeText(this, "Screen capture permission missing", Toast.LENGTH_SHORT).show();
            resetTranscriptionButton();
            return;
        }
        Intent overlayIntent = new Intent(this, FloatingToggleButtonService.class);
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(overlayIntent);
                } else {
                    startService(overlayIntent);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start FloatingToggleButtonService", e);
            resetTranscriptionButton();
            return;
        }

        showSec = true;
        FloatingToggleButtonService.showOverlay();
        mainButton.setImageResource(R.drawable.turn_off);
        mainButton.setEnabled(true);
        logUiState("startSecoundryButton:after");
    }

    /**
     * Stops the secondary floating button overlay and transcription
     */
    private void stopSecoundryButton() {
        Log.d(TAG, "stopSecoundryButton()");
        if(!showSec) return;
        showSec = false;
        FloatingToggleButtonService.hideOverlay();
        stopTranscription();
        mainButton.setImageResource(R.drawable.turn_on);
        mainButton.setEnabled(true);
        logUiState("stopSecoundryButton:after");
    }
    /**
     * Starts the transcription pipeline and sets listener for updates/errors
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void startTranscription(int resultCode, Intent data) {
        Log.d(TAG, "startTranscription called with resultCode=" + resultCode + ", data=" + data);
        if (running) return;
        running = true;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission missing", Toast.LENGTH_SHORT).show();
            running = false;
            return;
        }

        if (!hasProjectionPermission()) {
            Toast.makeText(this, "Screen capture permission missing", Toast.LENGTH_SHORT).show();
            running = false;
            return;
        }
        if (pipeline == null) {
            Log.e(TAG, "Pipeline not initialized yet");
            running = false;
            return;
        }

        try {
            pipeline.start();
            pipeline.setListener(new MainPipeline.Listener() {
                @Override
                public void onLanguageDetected(String lang) {
                }

                @Override
                public void onTranscriptionUpdate(String text) {

                }

                @Override
                public void onTransltionUpdate(String text) {

                }

                @Override
                public void onError(String e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Transcription error: " + e,
                            Toast.LENGTH_LONG).show());
                    Log.e("MainActivity", "Pipeline error: " + e);
                }
            });

        } catch (Exception e) {
            Toast.makeText(this, "Failed to start transcription", Toast.LENGTH_SHORT).show();
            running = false;
            Log.e("MainActivity", "startTranscription error", e);
        }
    }
    /**
     * Stops the transcription pipeline
     */
    private void stopTranscription() {
        Log.d(TAG, "stopTranscription()");
        if(!running) return;
        running = false;
        if (pipeline == null) return;
        try {
            pipeline.stop();
        } catch (Exception e) {
            Toast.makeText(this, "Error stopping transcription", Toast.LENGTH_SHORT).show();
            Log.e("MainActivity", "stopTranscription error", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissModelDownloadDialog();
        // Stop overlay services
        stopService(new Intent(this, FloatingToggleButtonService.class));
        stopService(new Intent(this, AudioCaptureService.class));
        // Unregister broadcast receivers
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceReadyReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(toggleServiceReadyReceiver);
        // Destroy pipeline resources
        if (pipeline != null) pipeline.destroy();
    }
}
