package com.example.subtitles.archive.vosk_legacy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;


import com.example.subtitles.model.transcription.core.VoskStreamTranscriber;
import com.example.subtitles.model.transcription.core.LanguageModelManager;
import com.example.subtitles.view_model.transcriptManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;


public class VoskWindowTranscriber {
    private static final String TAG = "VoskWindowTranscriber";
    private static final long MAX_RESET_INTERVAL = 60_000*5; // 5 minutes
    private static VoskWindowTranscriber instance;

    private static final Set<String> NOSPACE_LANGS = VoskStreamTranscriber.NOSPACE_LANGS;

    private boolean specialLanguageNotSpacedOut = false;

    private final Context context;
    private final float sampleRate;
    private final Handler mainHandler;
    private final BlockingQueue<short[]> audioQueue;
    private final AtomicBoolean running;
    private final StringBuilder transcriptSubtitles = new StringBuilder();
    private final Object lock = new Object();
    private final AtomicBoolean languageSwitchInProgress = new AtomicBoolean(false);
    private ExecutorService switchExecutor = Executors.newSingleThreadExecutor();
    private Model model;
    private Recognizer recognizer;
    private Thread workerThread;
    private String currentLang = "";
    private String modelPath = "";
    private long lastResetTime = System.currentTimeMillis();
    private Listener listener;

    private VoskWindowTranscriber(Context context) {
        this.context = context.getApplicationContext();
        this.sampleRate = transcriptManager.sampleRate;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.audioQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(false);
    }

    /**
     * @param context application context
     */
    public static VoskWindowTranscriber getInstance(Context context) {
        if (instance == null) {
            synchronized (VoskWindowTranscriber.class) {
                if (instance == null) {
                    instance = new VoskWindowTranscriber(context);
                }
            }
        }
        return instance;
    }

    /**
     * Sets the transcription listener.
     */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Switches to a new language model. Blocks until model loading completes or fails.
     * Stops any ongoing transcription.
     *
     * @param langCode ISO language code (e.g. "en").
     */
    private synchronized void switchLanguage(String langCode, String pathToModel) {
        if(pathToModel==null || pathToModel.isEmpty()) {
            try {
                LanguageModelManager mgr = LanguageModelManager.getInstance(context);
                File modelDir = mgr.loadModel(langCode);
                if (modelDir == null || !modelDir.isDirectory()) {
                    Log.e(TAG, "Model directory invalid for lang=" + langCode);
                    return;
                }
                pathToModel = modelDir.getAbsolutePath();
            } catch (Exception e) {
                Log.e(TAG, "Model load failed, cannot switch language to " + langCode, e);
                notifyError(e);
                return;
            }
        }

        try {
            if (modelPath.equals(pathToModel)) {
                Log.e(TAG, "same Model " + langCode);
                return;
            }
            // stop existing transcription
            stop();
            currentLang = langCode;
            modelPath = pathToModel;
            model = new Model(modelPath);
            resetTranscriber();
            Log.i(TAG, "Loaded model for language=" + langCode);
            // start transcription automatically
            start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Vosk for " + langCode, e);
            notifyError(e);
        }
    }

    public void switchLanguageAsync(String langCode, String pathToModel) {
        Log.i(TAG, "[switchLanguage] enter: langCode='" + langCode + "', currentLang='" + currentLang + "'");

        if (langCode == null || langCode.isEmpty()) {
            Log.i(TAG, "Language unchanged - problem input lang: " + langCode);
            return;
        }

        if (!currentLang.isEmpty() && langCode.equals(currentLang)) {
            Log.i(TAG, "Language unchanged: " + langCode);
            return;
        }

        // Try to set the flag; skip if already in progress
        if (!languageSwitchInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Language switch already in progress. Ignored request to switch to: " + langCode);
            return;
        }

        if (switchExecutor == null || switchExecutor.isShutdown()) {
            switchExecutor = Executors.newSingleThreadExecutor();
        }
        switchExecutor.submit(() -> {
            try {
                switchLanguage(langCode, pathToModel);
                specialLanguageNotSpacedOut = NOSPACE_LANGS.contains(currentLang);
            } finally {
                languageSwitchInProgress.set(false); // Always reset
            }
        });

    }


    /**
     * Starts transcription processing thread.
     */
    private synchronized void start() {
        if (model == null) {
            Log.i(TAG, "Cannot start: Model was null for lang - " + currentLang);
            return;
        }
        if (recognizer == null) {
            Log.w(TAG, "Cannot start: recognizer not initialized");
            return;
        }
        if (running.getAndSet(true)) {
            return;
        }
        workerThread = new Thread(this::processLoop, "VoskWorker");
        workerThread.start();
    }


    public void acceptAudio(short[] pcm, int length) {
        if (!running.get() || recognizer == null) return;
        short[] copy = Arrays.copyOf(pcm, length);
        audioQueue.offer(copy);
    }


    /**
     * Stops transcription and releases model and recognizer.
     */
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        if (workerThread != null) {
            workerThread.interrupt();
            if (Thread.currentThread() != workerThread) {
                try {
                    workerThread.join(500); 
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting for workerThread to finish", e);
                }
            } else {
                Log.w(TAG, "Avoided join on self-thread");
            }
            workerThread = null;
        }
        audioQueue.clear();
        transcriptSubtitles.setLength(0);
        if (recognizer != null) {
            try {
                synchronized (lock) {
                    recognizer.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "failed to close recognizer", e);
            }
            recognizer = null;
        }
        if (model != null) {
            model.close();
            model = null;
            modelPath = "";
        }
        currentLang = "";
    }

    private synchronized void resetTranscriber() {
        if (model == null) {
            Log.w(TAG, "resetTranscriber called but model not initialized");
            return;
        }
        Log.w(TAG, "reset Transcriber called and running...");

        try {
            recognizer = new Recognizer(model, sampleRate);
            Log.i(TAG, "Recognizer (re)initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create Recognizer during reset", e);
            notifyError(e);
            return;
        }

        lastResetTime = System.currentTimeMillis();
        transcriptSubtitles.setLength(0);
    }

    private void processLoop() {
        try {
            int windowSizeSamples = (int)(sampleRate * 20);
            short[] windowBuffer = new short[windowSizeSamples];
            int bufferPos = 0;
            boolean isFinal = true;
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                long now = System.currentTimeMillis();
                if (now - lastResetTime >= MAX_RESET_INTERVAL) {
                    // Smart fallback reset
                    resetTranscriber();
                    Log.i(TAG, "Auto-reset after long run");
                }

                short[] chunk;
                try {
                    chunk = audioQueue.take();
                } catch (InterruptedException e) {
                    Log.i(TAG, "Transcription thread interrupted — stopping normally.");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in transcription loop", e);
                    break;
                }

                if (isFinal) {
                    int toCopy = Math.min(chunk.length, windowSizeSamples - bufferPos);
                    System.arraycopy(chunk, 0, windowBuffer, bufferPos, toCopy);
                    bufferPos += toCopy;
                }

                if(bufferPos >= windowSizeSamples || !isFinal) {
                    String result = "";
                    if(!isFinal) {
                        synchronized (lock) {
                            if (recognizer == null) continue;
                            isFinal = recognizer.acceptWaveForm(chunk, chunk.length);
                            result = isFinal ? recognizer.getResult() : "";
                        }
                    } else {
                        synchronized (lock) {
                            if (recognizer == null) continue;
                            isFinal = recognizer.acceptWaveForm(windowBuffer, windowSizeSamples);
                            result = isFinal ? recognizer.getResult() : "";
                        }
                    }
                    // 5) clear audio buffer
                    bufferPos = 0;
                    if(isFinal) {
                        resetTranscriber();
                        Log.i(TAG, "------------ reset big transcription method results ---------");
                        handleTexts(result);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Transcription thread error", e);
            notifyError(e);
        }
    }

    private boolean handleTexts(String json) {
        try {
            if(json==null || json.isEmpty()) {
                return false;
            }
            synchronized (this) {
                JSONObject obj = new JSONObject(json);
                List<String> newWords = new ArrayList<>();
                Log.i(TAG, "🔚 FINAL JSON: " + json);
                JSONArray wordsArr = obj.optJSONArray("result");
                if (wordsArr != null) {
                    for (int i = 0; i < wordsArr.length(); i++) {
                        JSONObject w = wordsArr.getJSONObject(i);
                        newWords.add(w.getString("word"));
                    }
                } else {
                    String fallback = obj.optString("text", "").trim();
                    Log.w(TAG, "⚠️ Vosk final result has no words array. Fallback text=" + fallback);
                    if (!fallback.isEmpty()) {
                        if (specialLanguageNotSpacedOut) {
                            newWords.add(fallback);
                        } else {
                            newWords.addAll(Arrays.asList(fallback.split("\\s+")));
                        }
                    }
                }

                if (newWords.isEmpty()) {
                    return false;
                }

                String text = specialLanguageNotSpacedOut
                        ? String.join("", newWords)
                        : String.join(" ", newWords);

                transcriptSubtitles.replace(0, transcriptSubtitles.length(), text);

                notifyListener(transcriptSubtitles.toString());

                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to handle transcript text: ", e);
            return false;
        }
    }

    private void notifyListener(String currentText) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTranscriptionUpdate(currentText));
        }
    }

    private void notifyError(Exception e) {
        if (listener != null) {
            mainHandler.post(() -> listener.onError(e));
        }
    }

    public void destroy() {
        stop();
        switchExecutor.shutdownNow();
    }

    /**
     * Listener for full bug transcription callbacks.
     */
    public interface Listener {
        void onTranscriptionUpdate(String fullText);

        void onError(Exception e);
    }
}
