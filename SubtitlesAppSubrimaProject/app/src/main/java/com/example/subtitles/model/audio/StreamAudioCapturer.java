package com.example.subtitles.model.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Singleton component responsible for capturing device playback audio
 * using the MediaProjection + AudioPlaybackCapture API (Android 10+).
 *
 * Responsibilities:
 *  - Receives MediaProjection permission from service
 *  - Configures AudioRecord for playback capture
 *  - Continuously reads PCM audio chunks
 *  - Forwards audio data to a listener
 *  - Handles projection revocation and mid-capture restarts
 *
 * Threading model:
 *  - Control operations run on main thread
 *  - Audio capture occurs on a dedicated background thread
 *
 * This class contains NO UI logic and belongs strictly to the Model layer.
 */
public class StreamAudioCapturer {
    /** Duration of one audio chunk in milliseconds */
    public static final int chunkSizeMs = 250;
    private static final String TAG = "StreamAudioCapturer";
    /** Singleton instance */
    private static StreamAudioCapturer instance;
    private final Context context;
    private final MediaProjectionManager projectionManager;
    /** Target sample rate (e.g., 16000Hz) */
    private final int sampleRate;
    /** Chunk size in samples */
    private final int chunkSize;
    /** Indicates capture was stopped because projection was lost */
    private final AtomicBoolean stopedMidCapturing = new AtomicBoolean(false);
    /** Indicates capture is currently running */
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    /** Handler bound to main thread */
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Synchronization lock for recorder lifecycle */
    private final Object lock = new Object();
    private final AudioManager audioManager;
    /** Detects apps that block audio capture */
    private final AudioManager.AudioPlaybackCallback playbackCallback;
    /** Active MediaProjection instance */
    private MediaProjection projection;
    /** Audio recorder configured for playback capture */
    private AudioRecord recorder;
    /** Client listener receiving PCM data */
    private OnAudioCaptureListener listener;
    /** Background thread reading audio */
    private Thread captureThread;

    /**
     * Private constructor for singleton.
     */
    private StreamAudioCapturer(Context context, int sampleRate) {
        this.context = context.getApplicationContext();
        this.projectionManager = context.getSystemService(MediaProjectionManager.class);
        this.sampleRate = sampleRate;
        // Convert chunk duration to number of samples
        this.chunkSize = (chunkSizeMs * sampleRate) / 1000;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        /**
         * Callback triggered whenever playback configurations change.
         * Used to detect apps that explicitly block capture.
         */
        playbackCallback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                super.onPlaybackConfigChanged(configs);

                boolean blockedDetected = false;
                for (AudioPlaybackConfiguration cfg : configs) {
                    int usage = cfg.getAudioAttributes().getUsage();
                    int policy = cfg.getAudioAttributes().getAllowedCapturePolicy();
                    if ((usage == AudioAttributes.USAGE_MEDIA ||
                            usage == AudioAttributes.USAGE_GAME ||
                            usage == AudioAttributes.USAGE_ASSISTANT)
                            && policy == AudioAttributes.ALLOW_CAPTURE_BY_NONE) {
                        blockedDetected = true;
                        break;
                    }
                }

                if (blockedDetected) {
                    Log.w(TAG, "An application has been detected that plays audio but blocks capture.");
                    if (listener != null) {
                        listener.onCaptureBlockedDetected();
                    }
                }
            }
        };
    }
    /**
     * Returns singleton instance.
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static StreamAudioCapturer getInstance(Context context, int sampleRate) {
        if (instance == null) {
            synchronized (StreamAudioCapturer.class) {
                if (instance == null) {
                    instance = new StreamAudioCapturer(context, sampleRate);
                }
            }
        }
        return instance;
    }
    /**
     * Destroys singleton and releases all resources.
     */
    public static synchronized void destroyInstance() {
        if (instance != null) {
            instance.destroy();
            instance = null;
        }
    }
    /**
     * Registers listener for audio callbacks.
     */
    public void setOnAudioCaptureListener(OnAudioCaptureListener l) {
        this.listener = l;
    }

    /**
     * Called when MediaProjection permission is granted by the service.
     */
    public void onProjectionGranted(int resultCode, Intent data) {
        if (projection != null) return;
        projection = projectionManager.getMediaProjection(resultCode, data);
        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection (null)");
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.e(TAG, "⚠️ MediaProjection.onStop() called — projection permission revoked by system");
                Log.e(TAG, "   • isCapturing=" + capturing.get());
                Log.e(TAG, "   • stopedMidCapturing=" + stopedMidCapturing.get());
                // permissions dropped
                stop(true);
            }
        }, main);
        // Restart capture if permission returned after loss
        if (stopedMidCapturing.get()) {
            if (!start()) {
                Log.e(TAG, "Failed to start again capturing");
            }
        }
    }

    /**
     * Called when projection permission is revoked.
     */
    public void onProjectionRevoked() {
        stop(true);
        projection = null;
    }
    /**
     * Starts audio capture.
     *
     * @return true if started successfully
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public boolean start() {
        if (capturing.get()) return false;

        synchronized (lock) {
            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();

                int minBufBytes = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);

                int desiredBufBytes = chunkSize * 2; // short = 2 bytes
                int bufSizeBytes = Math.max(minBufBytes, desiredBufBytes);

                AudioPlaybackCaptureConfiguration.Builder configBuilder =
                        new AudioPlaybackCaptureConfiguration.Builder(projection);

                int[] usages = new int[]{
                        AudioAttributes.USAGE_MEDIA,
                        AudioAttributes.USAGE_GAME,
                        AudioAttributes.USAGE_UNKNOWN,
                        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                        AudioAttributes.USAGE_ASSISTANT
                };

                for (int usage : usages) {
                    try {
                        configBuilder.addMatchingUsage(usage);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Invalid usage: " + usage);
                    }
                }


                recorder = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufSizeBytes)
                        .setAudioPlaybackCaptureConfig(
                                configBuilder
                                        .build())
                        .build();

                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Recorder state after build(): " + recorder.getState()
                            + " (1=INITIALIZED, 0=UNINITIALIZED)");
                    Log.e(TAG, "Recorder not initialized");
                    recorder.release();
                    recorder = null;
                    return false;
                }
                recorder.startRecording();
                capturing.set(true);
                audioManager.registerAudioPlaybackCallback(playbackCallback, main);
                captureThread = new Thread(this::captureLoop, "AudioCaptureThread");
                captureThread.start();
                Log.i(TAG, "Audio capture started");
                return true;
            } catch (SecurityException e) {
                Log.e(TAG, "Permission error when starting capture", e);
                return false;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start audio capture", e);
                return false;
            }
        }
    }
    /**
     * Continuous PCM read loop.
     */
    private void captureLoop() {
        Log.d(TAG, "Entering capture loop with capturing=" + capturing.get());
        short[] buffer = new short[chunkSize];
        while (capturing.get() && !Thread.currentThread().isInterrupted()) {
            int read = recorder.read(buffer, 0, buffer.length);
            boolean errorRead = false;
            if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "ERROR_INVALID_OPERATION on read() — illegal state or permission dropped");
                errorRead = true;
            } else if (read == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "ERROR_BAD_VALUE on read() — bad arguments");
                errorRead = true;
            } else if (read < 0) {
                Log.e(TAG, "Unknown read error: " + read);
                errorRead = true;
            }
            if (errorRead) {
                Log.e(TAG, "Unexpected read error: " + read);
                break;
            } else if (read > 0 && listener != null) {
                listener.onAudioChunk(buffer, read);
            }

        }
    }
    /**
     * Stops capture.
     *
     * @param midCaptureing true if stop caused by projection loss
     */
    public void stop(boolean midCaptureing) {
        // if we were NOT capturing, nothing to do
        if (!capturing.getAndSet(false)) return;
        audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        synchronized (lock) {
            stopedMidCapturing.set(midCaptureing);
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
            if (captureThread != null) {
                captureThread.interrupt();
                try {
                    captureThread.join();
                } catch (InterruptedException ignored) {
                }
                captureThread = null;
            }
        }
        Log.i(TAG, "Audio capture stopped");
    }
    /**
     * Checks if any active playback source allows capture.
     */
    public boolean hasCapturableAudio() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        List<AudioPlaybackConfiguration> configs = am.getActivePlaybackConfigurations();

        for (AudioPlaybackConfiguration cfg : configs) {
            int usage = cfg.getAudioAttributes().getUsage();
            int policy = cfg.getAudioAttributes().getAllowedCapturePolicy();

            if ((usage == AudioAttributes.USAGE_MEDIA ||
                    usage == AudioAttributes.USAGE_GAME ||
                    usage == AudioAttributes.USAGE_UNKNOWN)
                    && policy != AudioAttributes.ALLOW_CAPTURE_BY_NONE) {
                return true; 
            }
        }
        return false;
    }

    /**
     * Fully destroys capturer.
     */
    private void destroy() {
        stop(false);
        synchronized (lock) {
            if (projection != null) {
                projection.stop();
                projection = null;
            }
        }
        Log.i(TAG, "AudioWindowCapturer destroyed");
    }
    /**
     * Listener for audio capture events.
     */
    public interface OnAudioCaptureListener {
        /** Raw PCM audio chunk received */
        void onAudioChunk(short[] pcm, int length);

        /** Playback source detected but capture is blocked */
        default void onCaptureBlockedDetected() {
            // default no-op
        }
    }


}

