// WhisperTranscriber.java
        package com.example.subtitles.model.transcription.correction.whisper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import android.os.Build;

import com.example.subtitles.model.transcription.correction.transcriptSegment;
import com.example.subtitles.model.transcription.correction.whisper.lib.WhisperContext;
import com.example.subtitles.util.AssetUtils;
import com.example.subtitles.view_model.MainPipeline;
import com.example.subtitles.view_model.transcriptManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ================================================================
 * WhisperTranscriber
 * ================================================================
 *
 * Singleton class for **streaming, chunked transcription** using Whisper.
 *
 * Responsibilities:
 *  - Append incoming audio in small chunks
 *  - Accumulate a 30s buffer window
 *  - Transcribe each window in order
 *  - Retain a small overlap (2s) for smoother continuity
 *  - Provide a listener interface for UI / downstream updates
 *  - Track language detection and processing performance
 *
 * Usage:
 *  1. Initialize singleton: getInstance(context)
 *  2. Start processing: start()
 *  3. Append audio chunks: appendAudio(samples)
 *  4. Stop processing: stop()
 *  5. Release resources: close()
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class WhisperTranscriber {
    /** Logging tag */
    private static final String TAG = "WhisperTranscriber";

    /** Whisper model filename in app internal storage */
    public static final String MODEL_PATH = AssetUtils.WHISPER_TINY_MODEL_FILE;

    /** Singleton instance */
    private static WhisperTranscriber instance;

    // ================================================================
    // Buffer & chunking configuration
    // ================================================================

    /** Audio sample rate (from transcriptManager) */
    private static final int SAMPLE_RATE     = transcriptManager.sampleRate;   // e.g., 16_000

    /** Size of a transcription chunk (seconds) */
    public static final int CHUNK_SEC        = 30;

    /** Overlap size between chunks (seconds) */
    public static final int OVERLAP_SEC      = 2;

    /** Number of samples in one chunk */
    private static final int CHUNK_SAMPLES    = SAMPLE_RATE * CHUNK_SEC;   // 160 000


    private final int chunkSamples = CHUNK_SEC * SAMPLE_RATE;      // 10s worth of samples
    /** Number of samples in overlap window */
    private final int overlapSamples = OVERLAP_SEC * SAMPLE_RATE;  // 2s worth of samples

    /** Accumulated buffer for streaming audio */
    private final float[] buffer = new float[CHUNK_SAMPLES];
    /** Current number of valid samples in buffer */
    private int bufferLen = 0;


    // ================================================================
    // Queue & threading
    // ================================================================

    /** Incoming audio queue */
    private final BlockingQueue<float[]> audioQueue;

    /** Thread running processing loop */
    private Thread processingThread;

    /** Whisper context for native transcription */
    private WhisperContext ctx = null;

    /** Flag to indicate processing done */
    private volatile boolean isDone = false;

    /** Listener for transcription results / errors */
    private Whisperlistener listener;

    /** Handler for posting results on main thread */
    private final Handler mainHandler;

    /** Guard for thread-safe running state */
    private final AtomicBoolean running;

    // ================================================================
    // Language / statistics
    // ================================================================

    /** Detected language from last segment */
    private String lang = "";
    /** Total number of transcriptions processed */
    private long counterProcessing;

    /** Sum of processing durations (ms) */
    private long sumProcessingTime;

    // ================================================================
    // Constructor
    // ================================================================

    /**
     * Private constructor for singleton.
     * Loads Whisper context and prepares queue / handler.
     */
    private WhisperTranscriber(Context context) {
        if (OVERLAP_SEC >= CHUNK_SEC) {
            throw new IllegalArgumentException("OVERLAP_SEC must be less than CHUNK_SEC");
        }
        this.ctx = WhisperContext.getInstance(context, MODEL_PATH);
        this.audioQueue = new LinkedBlockingQueue<>();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.running = new AtomicBoolean(false);
    }

    // ================================================================
    // Singleton access
    // ================================================================

    /**
     * Get singleton instance. Initializes context if necessary.
     *
     * @param appContext application context
     * @return WhisperTranscriber singleton
     */
    public static synchronized WhisperTranscriber getInstance(Context appContext) throws IOException {
        if (instance == null) {
            instance = new WhisperTranscriber(appContext);
            Log.i(TAG, "WhisperTranscriber initialized");
        }
        return instance;
    }

    // ================================================================
    // Audio input
    // ================================================================

    /**
     * Append raw audio samples for processing.
     * No-op if samples are null/empty or if processor is not running.
     *
     * @param samples PCM float array
     */
    public void appendAudio(float[] samples) {
        if (samples == null || samples.length == 0 || !running.get()) return;
        float[] copy = samples.clone();
        audioQueue.offer(copy);
    }

    // ================================================================
    // Main processing loop
    // ================================================================

    /**
     * Continuously takes audio chunks, accumulates buffer,
     * and transcribes each 30s window with 2s overlap.
     */
    private void processingLoop() {
        try {
            while (!isDone) {
                float[] chunk = audioQueue.take(); // blocks until available

                if (chunk != null && chunk.length > 0) {
                    int incoming = chunk.length;
                    // If buffer would overflow, drop oldest samples
                    if (bufferLen + incoming > buffer.length) {
                        int overflow = (bufferLen + incoming) - buffer.length;
                        // Drop oldest samples from start of buffer
                        System.arraycopy(buffer, overflow, buffer, 0, bufferLen - overflow);
                        bufferLen -= overflow;
                    }
                    // Append new samples
                    System.arraycopy(chunk, 0, buffer, bufferLen, incoming);
                    bufferLen += incoming;
                }

                // Transcribe once enough samples collected
                if (bufferLen >= chunkSamples) {
                    Log.d(TAG, "🟦 bufferLen = " + bufferLen + " / " + chunkSamples);
                    doTranscribe(chunkSamples);

                    // Retain last overlap window
                    System.arraycopy(
                            buffer,
                            chunkSamples - overlapSamples,
                            buffer,
                            0,
                            overlapSamples
                    );
                    bufferLen = overlapSamples;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt
        }
    }



    // ================================================================
    // Transcription helper
    // ================================================================

    /**
     * Transcribe first `len` samples in buffer using WhisperContext.
     * Updates language detection, performance stats, and notifies listener.
     *
     * @param len number of samples to transcribe
     */
    private void doTranscribe(int len) {
        if(ctx==null) return;
        float[] toTranscribe = Arrays.copyOf(buffer, len);
        try {
            long start = System.currentTimeMillis();
            Log.i(TAG, " Starting transcription of " + len + " samples...");
            List<transcriptSegment> segs = ctx.transcribeWithTime(toTranscribe);
            long duration = System.currentTimeMillis() - start;
            Log.i(TAG, " Transcription completed in " + duration + "ms, " + segs.size() + " segments");
            if(!segs.isEmpty()) lang = ctx.detectLanguage();
            sumProcessingTime += duration;
            counterProcessing += 1;
            // If transcription took longer than expected, reset
            if(duration/1000L>CHUNK_SEC) {
                resetAll();
            }
            if(!segs.isEmpty()) notifyListener(segs, duration/1000L>CHUNK_SEC);
        } catch (Exception e) {
            Log.e(TAG, " Error during transcription", e);
        }

        // Shift remaining samples
        int remaining = bufferLen - len;
        if (remaining > 0) {
            System.arraycopy(buffer, len, buffer, 0, remaining);
        }
        bufferLen = Math.max(0, remaining);
    }
    // ================================================================
    // Reset / cleanup
    // ================================================================

    /** Fully reset buffer, queue, language, and stats */
    private void resetAll() {
        audioQueue.clear();
        bufferLen = 0;
        lang = "";
        counterProcessing = 0;
        sumProcessingTime = 0;
    }
    /**
     * Start transcription thread.
     */
    public synchronized void start() {
        if (running.getAndSet(true)) {
            return;
        }
        resetAll();
        this.processingThread = new Thread(this::processingLoop, "WhisperProcessor");
        this.processingThread.start();
    }
    /**
     * Stop transcription thread. Optionally execute callback on stop.
     */
    public synchronized void stop(@Nullable Runnable onStopped) {
        if (!running.getAndSet(false)) {
            if (onStopped != null) onStopped.run();
            return;
        }
        isDone = true;
        resetAll();
        new Thread(() -> {
            try {
                processingThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notifyError(e);
            }

            if (counterProcessing > 0) {
                long avg = sumProcessingTime / counterProcessing;
                Log.i(TAG, " WhisperTranscriber stopped — total=" + counterProcessing +
                        " transcriptions, avg=" + avg + "ms");
            } else {
                Log.i(TAG, " WhisperTranscriber stopped — no transcriptions performed.");
            }

            if (onStopped != null) {
                mainHandler.post(onStopped);
            }
        }).start();
    }


    /**
     * Close resources. Should be called after finalTranscription().
     */
    public synchronized void close() {
        stop(()->{});
        try {
            if(ctx!=null) ctx.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing WhisperContext", e);
        }
        Log.i(TAG, "WhisperTranscriber closed");
    }

    // ================================================================
    // Listener
    // ================================================================

    /** Set listener for transcription updates and errors */
    public void setListener(Whisperlistener listener) {
        this.listener = listener;
    }

    /** Returns currently detected language */
    public String getCurrentLang() {
        return lang;
    }

    /** Notify listener on new segments */
    private void notifyListener(List<transcriptSegment> currentSegment, boolean proformReset) {
        // make update listener
        if (listener != null) {
            mainHandler.post(() -> listener.onResult(currentSegment, proformReset));
        }
    }
    /** Notify listener of errors */
    private void notifyError(Exception e) {
        if (listener != null) {
            mainHandler.post(() -> listener.onError(e));
        }
    }

    // ================================================================
    // Listener interface
    // ================================================================

    public interface Whisperlistener {

        /** Called on new transcription segments */
        void onResult(@NonNull List<transcriptSegment> text, boolean proformReset);

        /** Called on error during processing */
        void onError(@NonNull Exception e);
    }
}
