package com.example.subtitles;

import static android.content.ContentValues.TAG;

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

import com.example.subtitles.model.audio.AudioCaptureService;
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

    /// Synchronization lock for UI and service calls
    private final Object lock = new Object();

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
                Log.d(TAG, "serviceIsReady=true, isTranscribing=" + showSec
                        + ", hasProjectionPermission=" + hasProjectionPermission()
                        + ", canDrawOverlays=" + Settings.canDrawOverlays(MainActivity.this));

                runOnUiThread(() -> {
                    // If overlay and screen capture permissions are granted and secondary button not shown, start it
                    if (!showSec && hasProjectionPermission() && Settings.canDrawOverlays(MainActivity.this)) {
                        Log.d(TAG, "Service ready and permissions OK, starting transcription");
                        synchronized (lock) {
                            startSecoundryButton(projectionResultCode, projectionData);
                        }
                    } else {
                        // Otherwise, reset the main button UI
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
                if (!secListenerRegistered) {
                    // Register listener to respond when user toggles the floating button
                    FloatingToggleButtonService.registerListener(isOn -> {
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

                        // Start the AudioCaptureService with projection data
                        Intent serviceIntent = new Intent(this, AudioCaptureService.class);
                        serviceIntent.putExtra("PROJECTION_CODE", projectionResultCode);
                        serviceIntent.putExtra("PROJECTION_DATA", projectionData);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent);
                        } else {
                            startService(serviceIntent);
                        }

                    } else {
                        Log.d(TAG, "Projection permission denied or data null");
                        Toast.makeText(this, "Capture permission denied", Toast.LENGTH_SHORT).show();
                        resetTranscriptionButton();
                    }
                });
        // Setup main toggle button click listener
        mainButton.setImageResource(R.drawable.turn_on);
        mainButton.setOnClickListener(v -> {
            mainButton.setEnabled(false); // Disable until action completes
            if (!showSec) {
                // Start secondary button & transcription
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
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
                    startProjectionRequest();
                } else if (serviceIsReady) {
                    synchronized (lock) {
                        startSecoundryButton(projectionResultCode, projectionData);
                    }
                }
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



    }

    /**
     * Resets the main button UI (icon and enabled state)
     */
    private void resetTranscriptionButton() {
        mainButton.setEnabled(true);
        mainButton.setImageResource(R.drawable.turn_on);
    }

    /**
     * Starts overlay permission request flow and/or screen capture request
     */
    private void startProjectionRequest() {
        Toast.makeText(this, "Please grant overlay permission to start subtitles", Toast.LENGTH_SHORT).show();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                overlayPermissionLauncher.launch(intent);
                return;
            }

            requestScreenCapturePermission();
        } else {
            Toast.makeText(this, "Requires Android 10+", Toast.LENGTH_SHORT).show();
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
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        screenCaptureLauncher.launch(captureIntent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted");
            } else {
                Toast.makeText(this,
                        "Audio capture permission is required for subtitles",
                        Toast.LENGTH_LONG).show();
                mainButton.setEnabled(false);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        if (showSec) return;
        showSec = true;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission missing", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasProjectionPermission()) {
            Toast.makeText(this, "Screen capture permission missing", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent overlayIntent = new Intent(this, FloatingToggleButtonService.class);
        new Handler(Looper.getMainLooper()).post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(overlayIntent);
            } else {
                startService(overlayIntent);
            }
        });

        FloatingToggleButtonService.showOverlay();
        mainButton.setImageResource(R.drawable.turn_off);
        mainButton.setEnabled(true);
    }

    /**
     * Stops the secondary floating button overlay and transcription
     */
    private void stopSecoundryButton() {
        if(!showSec) return;
        showSec = false;
        FloatingToggleButtonService.hideOverlay();
        stopTranscription();
        mainButton.setImageResource(R.drawable.turn_on);
        mainButton.setEnabled(true);
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
