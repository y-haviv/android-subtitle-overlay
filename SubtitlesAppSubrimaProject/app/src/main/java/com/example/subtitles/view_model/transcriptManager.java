package com.example.subtitles.view_model;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.subtitles.model.audio.StreamAudioCapturer;
import com.example.subtitles.model.language.SileroLanguageDetector;
import com.example.subtitles.model.transcription.core.TaggedAudioChunk;
import com.example.subtitles.model.transcription.correction.transcriptSegment;
import com.example.subtitles.model.transcription.correction.Dictionary;
import com.example.subtitles.model.transcription.core.VoskStreamTranscriber;
import com.example.subtitles.model.transcription.core.LanguageModelManager;
import com.example.subtitles.model.transcription.correction.whisper.WhisperTranscriber;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * transcriptManager is the central class managing the transcription pipeline.
 * It handles:
 * - Capturing device audio via StreamAudioCapturer
 * - Automatic source language detection (SileroLanguageDetector)
 * - Real-time transcription (VoskStreamTranscriber)
 * - Optional "smart correction" using WhisperTranscriber and dictionary-based correction
 *
 * This class is implemented as a singleton to ensure a single shared transcription pipeline.
 */
public class transcriptManager {

    //// Audio capture parameters
    public static final int sampleRate = 16000;

    /// Tag used for logging
    private static final String TAG = "transcriptManager";

    /// Singleton instance
    private static transcriptManager instance;

    /// Application context
    private final Context context;

    /// Captures the audio from the device
    private final StreamAudioCapturer capturer;

    /// Language identification detector for automatic source language detection
    private final SileroLanguageDetector lidDetector;

    /// Atomic flag to track whether the transcription pipeline is running
    private final AtomicBoolean running;

    /// Mapping of supported translation languages (used to validate changes)
    private final JSONObject googleLangMap;
    /// Vosk-based transcription engine
    private final VoskStreamTranscriber transcriber;

    /// Lock for synchronizing access to Vosk transcription history
    private final Object lockVoskHistory = new Object();

    /// Keeps the recent transcription segments from Vosk
    private final List<transcriptSegment> voskHistory = new ArrayList<>();

    /// Dictionary for smart correction of transcriptions
    private final Dictionary dictionary;

    /// Flags and state variables
    private boolean langInitialized = false;       // whether the initial language detection completed
    private boolean statusSrcLangDetected = true;  // whether source language is set via auto-detection
    private String srcLang;                         // current source language code
    private Listener listener;                      // callback interface for updates/errors

    /**
     * Whisper-based transcription for large audio windows and optional correction
     * - whisperCorrection: whether to enable runtime correction
     * - chunckCounter: tracks number of audio chunks appended to Whisper
     * - resetVoskHistory: indicates whether Vosk history should be cleared after Whisper processing
     */
    private boolean whisperCorrection = false;
    private int chunckCounter = 0;
    private WhisperTranscriber whisperT;
    private boolean resetVoskHistory = false;


    /**
     * Private constructor initializes the transcription pipeline:
     * - Sets up audio capture
     * - Initializes language identification
     * - Configures Vosk transcription listener
     *
     * @param context       Application context
     * @param srcL          Initial source language code
     * @param googleLangMap Supported translation languages
     */
    private transcriptManager(Context context, String srcL, JSONObject googleLangMap) {
        this.googleLangMap = googleLangMap;
        this.context = context.getApplicationContext();
        this.running = new AtomicBoolean(false);
        this.dictionary = Dictionary.getInstance();
        // Initialize audio capture
        this.capturer = StreamAudioCapturer.getInstance(context, sampleRate);

        this.srcLang = srcL;
        // Initialize Vosk transcription engine
        this.transcriber = VoskStreamTranscriber.getInstance(context);
        // Initialize language identification detector
        SileroLanguageDetector tempDetector = null;
        try {
            tempDetector = new SileroLanguageDetector(context, sampleRate);
        } catch (Exception e) {
            Log.e(TAG, "LID initialization failed", e);
            notifyError("LID initialization Error: " + e.getMessage());
        }
        this.lidDetector = tempDetector;
        // Set up audio capture listener
        this.capturer.setOnAudioCaptureListener(new StreamAudioCapturer.OnAudioCaptureListener() {
            @Override
            public void onAudioChunk(short[] pcm, int length) {
                try {
                    if (!running.get()) return;

                    // Wrap captured audio chunk into TaggedAudioChunk
                    TaggedAudioChunk chunk = new TaggedAudioChunk(Arrays.copyOf(pcm, length), (long) chunckCounter * StreamAudioCapturer.chunkSizeMs);
                    // Detect language using LID if enabled
                    if (lidDetector != null && statusSrcLangDetected) {
                        String detected = lidDetector.acceptChunk(chunk.getFloatAudio(), srcLang);
                        if (!detected.isEmpty() && (!detected.equals(srcLang) || !langInitialized)) {
                            langInitialized = true;
                            //lidConforimLangChang = true;
                            if (listener != null) {
                                listener.onLanguageDetected(detected);
                                checkValidLangBeforeChange(detected);
                            }
                            Log.d(TAG, "new lang detected: " + detected);
                        }
                    }

                    // Pass audio chunk to Vosk transcription engine
                    transcriber.acceptAudio(chunk);

                    // Append to WhisperTranscriber if smart correction enabled
                    if (whisperT != null && whisperCorrection) {
                        whisperT.appendAudio(chunk.getFloatAudio());
                        chunckCounter += 1;
                    }
                } catch (Exception e) {
                    notifyError("Audio Chunk Error: " + e.getMessage());
                }
            }

            @Override
            public void onCaptureBlockedDetected() {
                // Audio capture is blocked by the system or app overlay
                // Pipeline continues, but notify overlay if needed
            }
        });


        // Set Vosk transcription listener
        this.transcriber.setListener(new VoskStreamTranscriber.Listener() {
            @Override
            public void onTranscriptionUpdate(String lastSourceSentence, String fullText) {
                // Apply dictionary-based correction if enabled
                String sub = fullText;
                if (whisperCorrection) {
                    sub = dictionary.getCorrection(srcLang, fullText);
                }
                if (listener != null) {
                    listener.onTranscriptionUpdate(lastSourceSentence, sub);
                }
            }

            @Override
            public void onFinalResult(transcriptSegment seg) {
                // Only relevant when Whisper-based correction is enabled
                if (!whisperCorrection) return;
                if (resetVoskHistory) {
                    resetVoskHistory = false;
                    return;
                }
                synchronized (lockVoskHistory) {
                    voskHistory.add(seg);
                }
            }

            @Override
            public void onError(Exception e) {
                notifyError("Vosk Error: " + e.getMessage());
            }

            @Override
            public void onModelChange(String newLang, String modelPath) {
                if (!newLang.isEmpty() && (!newLang.equals(srcLang))) {
                    srcLang = newLang;
                    if (listener != null) listener.onModelTranscriptChange(srcLang);
                }
            }
        });
    }

    /**
     * Returns the singleton instance of transcriptManager.
     * Thread-safe double-checked locking.
     */
    public static transcriptManager getInstance(Context context, String srcLang, JSONObject googleLangMap) {
        if (instance == null) {
            synchronized (transcriptManager.class) {
                if (instance == null) {
                    instance = new transcriptManager(context, srcLang, googleLangMap);
                }
            }
        }
        return instance;
    }
    /**
     * Initializes WhisperTranscriber for smart correction
     */
    private void initializeWhisper() {
        try {
            this.whisperT = WhisperTranscriber.getInstance(context);
            this.whisperT.setListener(new WhisperTranscriber.Whisperlistener() {
                @Override
                public void onResult(@NonNull List<transcriptSegment> segments, boolean proformReset) {
                    resetVoskHistory = proformReset;
                    chunckCounter = 0;
                    if (segments.isEmpty()) return;

                    String newLang = whisperT.getCurrentLang();
                    Log.d(TAG, " Whisper language: " + newLang);

                    // Determine time window of Whisper segments
                    long startW = segments.get(0).getStart();
                    long endW = segments.get(segments.size() - 1).getEnd();

                    Log.d(TAG, " Whisper Result:");
                    for (transcriptSegment seg : segments) {
                        seg.normalize();
                        Log.d(TAG, " [" + seg.getStart() + " - " + seg.getEnd() + " ms] " + seg.getSentence());
                    }
                    Log.d(TAG, " Whisper time window: [" + startW + " - " + endW + "] ms");


                    // Split Vosk segments into matching and leftover segments for correction
                    List<transcriptSegment> toProcess = new ArrayList<>();
                    List<transcriptSegment> leftover = new ArrayList<>();
                    synchronized (lockVoskHistory) {
                        for (transcriptSegment v : voskHistory) {
                            v.normalize();
                            if (v.getStart() >= startW - StreamAudioCapturer.chunkSizeMs && v.getEnd() <= endW + 1000L) {
                                toProcess.add(v);
                            } else {
                                Log.d(TAG, "Vosk LeftOver:");
                                Log.d(TAG, "--- [" + v.getStart() + " - " + v.getEnd() + " ms] " + v.getSentence());
                                v.voskAjustTime(); // -30 sec (whisper audio window)
                                leftover.add(v);
                            }
                        }
                        voskHistory.clear();
                        if (!proformReset) {
                            voskHistory.addAll(leftover);
                        }
                    }

                    Log.d(TAG, " Matched Vosk Segments:");
                    for (transcriptSegment v : toProcess) {
                        Log.d(TAG, " [" + v.getStart() + " - " + v.getEnd() + " ms] " + v.getSentence());
                    }

                    // Update dictionary with corrected segments
                    dictionary.addCorrections(srcLang, toProcess, segments);

                }

                @Override
                public void onError(@NonNull Exception e) {
                    notifyError("Whisper Error: " + e.getMessage());
                }
            });
            this.whisperT.start();
            whisperCorrection = true;
        } catch (Exception e) {
            whisperCorrection = false;
            Log.e(TAG, "whisperT initialization failed", e);
        }
    }
    /**
     * Validates a newly detected language before switching transcription models.
     */
    private synchronized void checkValidLangBeforeChange(String newLang) {
        if (!running.get() || newLang.equals(srcLang)) return;

        Log.d(TAG, "new lang detected: " + newLang);
        // Map language to Google Translate code
        if (googleLangMap.has(newLang)) {
            String googleLang = googleLangMap.optString(newLang, null);
            Log.d(TAG, "Google Translate language code for '" + newLang + "' is: " + googleLang);
            newLang = googleLang;
        } else {
            Log.w(TAG, "Detected language '" + newLang + "' is not supported by Google Translate, ignoring.");
            return;
        }
        // Switch transcription model if supported
        try {
            if (LanguageModelManager.getInstance(context).isLanguageSupported(newLang)) {
                if (listener != null) listener.onLanguageChange(newLang);
                if (whisperCorrection) dictionary.clear();
                transcriber.switchLanguageAsync(newLang);
            } else {
                Log.i(TAG, "problem: it seems that vosk dont support this lang");
            }
        } catch (Exception e) {
            Log.i(TAG, "ERROR: transcript lang change -> checkValidLangBeforeChange");
        }
    }
    /**
     * Sets pipeline parameters from SharedPreferences
     */
    public void setParmeters() {
        if (!running.get()) {
            Log.i(TAG, "not started (running) yet there is no need to do it now...");
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences("subrima_prefs", MODE_PRIVATE);
        String tempSrc = prefs.getString("pref_source_lang", "auto");
        boolean tempCorrectionMode = prefs.getBoolean("pref_smart_correction", false);
        if (tempSrc.equals("auto")) {
            if (lidDetector != null) {
                statusSrcLangDetected = true;
                if (srcLang.isEmpty()) {
                    srcLang = "en";
                }
            } else {
                notifyError("lid lang detected problem initionlized....");
                statusSrcLangDetected = false;
                if (srcLang.isEmpty()) {
                    srcLang = "en";
                }
            }
        } else {
            statusSrcLangDetected = false;
            if (lidDetector != null) lidDetector.stop();
            srcLang = tempSrc;
        }
        // Reset history and counters
        resetVoskHistory = false;
        chunckCounter = 0;
        dictionary.clear();
        synchronized (lockVoskHistory) {
            voskHistory.clear();
        }
        // Initialize or stop Whisper based on correction preference
        if (tempCorrectionMode) {
            if (whisperT == null) {
                initializeWhisper();
            } else {
                this.whisperT.start();
                whisperCorrection = true;
            }
        } else {
            if (whisperT != null) {
                whisperT.stop(() -> {
                    Log.i(TAG, "WhisperTranscriber fully stopped");
                });
            }
            whisperCorrection = false;
        }

    }
    /**
     * Starts the transcription pipeline
     */
    public synchronized boolean start() {
        if (running.getAndSet(true)) {
            Log.i(TAG, "Already running");
            return false;
        }
        setParmeters();
        transcriber.switchLanguageAsync(srcLang);
        boolean started = capturer.start();
        if (!started) {
            stop();
            Log.w(TAG, "Audio capture failed to start");
            notifyError("Audio capture failed to start");
        }
        return started;
    }
    /**
     * Stops the transcription pipeline
     */
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        capturer.stop(false);
        transcriber.stop();
        if (srcLang.isEmpty()) {
            srcLang = "en";
        }

        if (whisperCorrection) {
            resetVoskHistory = false;
            chunckCounter = 0;
            if (whisperT != null) {
                whisperT.stop(() -> {
                    Log.i(TAG, "WhisperTranscriber fully stopped");
                });
            }
            dictionary.clear();
            synchronized (lockVoskHistory) {
                voskHistory.clear();
            }
        }
    }
    /**
     * Cleans up all resources and stops the pipeline completely
     */
    public synchronized void close() {
        stop();
        StreamAudioCapturer.destroyInstance();
        try {
            lidDetector.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing LID", e);
        }
        transcriber.destroy();
        whisperT.close();
        Log.i(TAG, "Pipeline destroyed");
    }


    /**
     * Sets the listener for pipeline events
     */
    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * Notifies the listener of an error
     */
    private void notifyError(String e) {
        if (listener != null) listener.onError(e);
    }

    /**
     * Listener interface for observing pipeline events
     */
    public interface Listener {
        void onLanguageDetected(String lang);

        void onLanguageChange(String lang);

        void onModelTranscriptChange(String lang);

        void onTranscriptionUpdate(String lastResult, String currentText);

        void onError(String e);
    }

}
