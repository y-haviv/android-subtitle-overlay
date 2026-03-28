package com.example.subtitles.model.transcription.correction.whisper.lib;

import android.content.Context;
import androidx.annotation.RequiresApi;
import android.os.Build;
import android.util.Log;

import com.example.subtitles.model.transcription.correction.transcriptSegment;
import com.example.subtitles.util.AssetUtils;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ================================================================
 * WhisperContext
 * ================================================================
 *
 * Singleton wrapper around native Whisper context.
 *
 * Responsibilities:
 *  - Load Whisper model from app internal storage (runtime-downloaded file)
 *  - Manage native context lifetime
 *  - Execute transcription on background thread
 *  - Convert native segments into transcriptSegment objects
 *
 * All JNI calls are routed through WhisperLib.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class WhisperContext {
    /** Logging tag */
    private static final String TAG = "WhisperContext";

    /** Singleton instance */
    private static WhisperContext instance;

    /** Native whisper_context pointer */
    private final long ctxPtr;

    /** Serial executor for native calls */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Guards against double initialization */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Private constructor.
     */
    private WhisperContext(long ptr) {
        this.ctxPtr = ptr;
    }

    // ================================================================
    // Initialization
    // ================================================================

    /**
     * Initialize singleton using model file from app internal storage.
     *
     * @param appContext application context
     * @param modelFileName model file name inside context.getFilesDir()
     */
    public static void init(Context appContext, String modelFileName) throws IOException {
        if (!initialized.compareAndSet(false, true)) {
            Log.i(TAG, "WhisperContext already initialized.");
            return;
        }
        if (instance != null) return;

        File modelFile = AssetUtils.getRequiredRuntimeModelFile(appContext, modelFileName);
        long ptr = WhisperLib.initContext(modelFile.getAbsolutePath());
        if (ptr == 0L)
            throw new RuntimeException(" Failed to init Whisper from file: " + modelFile.getAbsolutePath());

        instance = new WhisperContext(ptr);
        String sysInfo = WhisperLib.getSystemInfo();
        Log.i("WHISPER", "System Info: " + sysInfo);


        Log.i(TAG, " WhisperContext initialized from file: " + modelFile.getAbsolutePath());
    }



    /**
     * Returns singleton instance.
     * init() must be called first.
     */
    public static synchronized WhisperContext getInstance(Context ctx, String modelFileName) {
        if (instance == null) {
            Log.i(TAG,"WhisperContext not initialized. Call init() first.");
            try {
                WhisperContext.init(ctx, modelFileName);
                return instance;
            } catch (Exception e) {
                throw new RuntimeException("WhisperContex error initionlized whisper model: " + e.toString());
            }

        }
        return instance;
    }
    // ================================================================
    // Threading
    // ================================================================

    /**
     * Chooses reasonable number of CPU threads.
     */
    private int pickThreadCount() {
        int totalCores = Runtime.getRuntime().availableProcessors();
        // Leave cores for UI & system
        int chosenThreads = Math.max(1, Math.min(3, totalCores - 2));

        Log.i(TAG, " Available processors: " + totalCores);
        Log.i(TAG, " Threads chosen for Whisper: " + chosenThreads);

        return chosenThreads;
    }

    // ================================================================
    // Transcription
    // ================================================================

    /**
     * Performs synchronous transcription and returns segments with timestamps.
     *
     * @param audioData PCM float audio buffer
     * @return list of transcript segments
     */
    public List<transcriptSegment> transcribeWithTime(final float[] audioData)
            throws ExecutionException, InterruptedException {
        return executor.submit(new Callable<List<transcriptSegment>>() {
            @Override
            public List<transcriptSegment> call() {
                // Run native inference
                WhisperLib.fullTranscribe(ctxPtr, pickThreadCount(), audioData);
                int n = WhisperLib.getTextSegmentCount(ctxPtr);
                List<transcriptSegment> segs = new ArrayList<>(n);
                // Convert native segments
                for (int i = 0; i < n; i++) {
                    // Whisper time unit is 10ms
                    long t0 = WhisperLib.getTextSegmentT0(ctxPtr, i)*10;
                    long t1 = WhisperLib.getTextSegmentT1(ctxPtr, i)*10;
                    String txt = WhisperLib.getTextSegment(ctxPtr, i);
                    segs.add(new transcriptSegment(t0, t1, txt));
                }
                return segs;
            }
        }).get();
    }
    /**
     * @return detected language of last transcription
     */
    public String detectLanguage() {
        return WhisperLib.getDetectedLanguage(ctxPtr);
    }

    // ================================================================
    // Cleanup
    // ================================================================

    /**
     * Frees native resources and shuts down executor.
     */
    public void close() throws ExecutionException, InterruptedException {
        executor.submit(() -> WhisperLib.freeContext(ctxPtr)).get();
        executor.shutdown();
        instance = null;
        Log.i(TAG, "WhisperContext closed");
    }
}

