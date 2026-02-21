package com.example.subtitles.model.transcription.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.subtitles.model.audio.StreamAudioCapturer;
import com.example.subtitles.model.speaker.SpeakerChangeDetector;
import com.example.subtitles.model.transcription.core.TaggedAudioChunk;
import com.example.subtitles.model.transcription.correction.transcriptSegment;
import com.example.subtitles.model.transcription.core.LanguageModelManager;
import com.example.subtitles.view_model.MainPipeline;
import com.example.subtitles.view_model.transcriptManager;


import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ================================================================
 * VoskStreamTranscriber
 * ================================================================
 *
 * Performs real-time speech-to-text transcription using Vosk's
 * streaming recognizer.
 *
 * Key responsibilities:
 *  - Load and switch language models dynamically
 *  - Accept raw audio chunks
 *  - Run recognition on a background worker thread
 *  - Detect silence and speaker changes
 *  - Produce partial and final subtitle updates
 *
 * Designed as a thread-safe Singleton.
 */
public class VoskStreamTranscriber {
    /** Logging tag */
    private static final String TAG = "VoskStreamTranscriber";

    /** Hard fallback reset after this time (ms) */
    private static final long MAX_RESET_INTERVAL = 60_000; // 60 seconds

    /** Silence threshold that triggers smart reset */
    private static final long MAX_SILENCE_MS = 3000;

    /**
     * Languages that do NOT use whitespace between words.
     * For these languages we treat characters as units.
     */
    public static final Set<String> NOSPACE_LANGS = Set.of(
            "zh", "zh-CN", "zh-TW", "zh-HK",
            "ja", "th", "lo", "km", "my", "bo"
    );
    /** Singleton instance */
    private static VoskStreamTranscriber instance;

    /** Application context */
    private final Context context;

    /** Audio sample rate */
    private final float sampleRate;

    /** Handler for delivering callbacks on UI thread */
    private final Handler mainHandler;

    /** Queue of audio chunks awaiting transcription */
    private final BlockingQueue<TaggedAudioChunk> audioQueue;

    /** Indicates if worker thread is active */
    private final AtomicBoolean running;
    /** Holds current subtitle line */
    private final StringBuilder transcriptSubtitles = new StringBuilder();

    /** Synchronization lock for recognizer access */
    private final Object lock = new Object();

    /** Prevents concurrent language switches */
    private final AtomicBoolean languageSwitchInProgress = new AtomicBoolean(false);

    /** Executor used for async model switching */
    private ExecutorService switchExecutor = Executors.newSingleThreadExecutor();

    /** Active Vosk model */
    private Model model;
    /** Active recognizer */
    private Recognizer recognizer;

    /** Background worker thread */
    private Thread workerThread;

    /** Current ISO language code */
    private String currentLang = "";

    /** Absolute path of current model */
    private String modelPath = "";

    /** Last subtitle sent to UI */
    private String lastNotified = "";

    /** Last raw sentence before trimming */
    private String lastNotifiedUnModify = "";

    /** Timestamp of last reset */
    private long lastResetTime = System.currentTimeMillis();

    /** Timestamp when last word was detected */
    private long lastWordDetectedTime = System.currentTimeMillis();

    /** Accumulated finalized sentence */
    private String lastSourceSentence = "";

    /** UI listener */
    private Listener listener;
    /** Indicates whether last reset recreated recognizer */
    private boolean lastResetWasFull = true;

    /** True if current language is character-based */
    private boolean specialLanguageNotSpacedOut = false;

    /** Speaker change detector */
    private SpeakerChangeDetector speakerChange;

    /** Number of units already cut from front */
    private int cutPrefixLength = 0;

    /** Segment start timestamp */
    private long segStart = 0;

    /** Segment end timestamp */
    private long segEnd = 0;

    /** Indicates if last chunk ended a final segment */
    private boolean wasFinalSeg = true;

    /**
     * Private constructor (Singleton).
     */
    private VoskStreamTranscriber(Context context) {
        this.context = context.getApplicationContext();
        this.sampleRate = transcriptManager.sampleRate;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.audioQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(false);
        try {
            //speakerChange = new SpeakerChangeDetector(context);
            speakerChange = SpeakerChangeDetector.getInstance(context);
        } catch (Exception e) {
            Log.e(TAG, "SpeakerChangeDetector initialization FAILED. Disabled.", e);
            speakerChange = null; 
        }
    }

    /**
     * Returns singleton instance.
     */
    public static VoskStreamTranscriber getInstance(Context context) {
        if (instance == null) {
            synchronized (VoskStreamTranscriber.class) {
                if (instance == null) {
                    instance = new VoskStreamTranscriber(context);
                }
            }
        }
        return instance;
    }

    /**
     * Assign listener for transcription callbacks.
     */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Switch language synchronously.
     * Stops transcription, loads model, restarts engine.
     */
    private synchronized void switchLanguage(String langCode) {
        File modelDir;
        try {
            //VoskWindowTranscriber.getInstance(context).stop();
            LanguageModelManager mgr = LanguageModelManager.getInstance(context);
            modelDir = mgr.loadModel(langCode);
            if (modelDir == null || !modelDir.isDirectory()) {
                Log.e(TAG, "Model directory invalid for lang=" + langCode);
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Model load failed, cannot switch language to " + langCode, e);
            notifyError(e);
            return;
        }

        try {
            if (modelPath.equals(modelDir.getAbsolutePath())) {
                Log.e(TAG, "same Model " + langCode);
                return;
            }
            // stop existing transcription
            stop();
            notifyListener("", "new language detected: " + langCode);
            lastNotified = "";
            lastNotifiedUnModify = "";
            currentLang = langCode;
            modelPath = modelDir.getAbsolutePath();
            model = new Model(modelPath);
            resetTranscriber(true);
            Log.i(TAG, "Loaded model for language=" + langCode);
            // start transcription automatically
            start();
            //VoskWindowTranscriber.getInstance(context).switchLanguageAsync(langCode, modelPath);
            notifyLangChange(currentLang, modelPath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Vosk for " + langCode, e);
            notifyError(e);
        }
    }
    /**
     * Switch language asynchronously.
     */
    public void switchLanguageAsync(String langCode) {
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
                switchLanguage(langCode);
                specialLanguageNotSpacedOut = NOSPACE_LANGS.contains(currentLang);
            } finally {
                languageSwitchInProgress.set(false); // Always reset
            }
        });

    }


    /**
     * Starts worker thread.
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

    /**
     * Enqueue audio chunk for transcription.
     */
    public void acceptAudio(TaggedAudioChunk chunk) {
        if (!running.get() || recognizer == null) return;

        try {
            if (speakerChange != null && speakerChange.acceptAudio(chunk.getShortAudio())) {
                chunk.setResetBefore();
                Log.i(TAG, String.format("🎙 Speaker change → tagged queue chunk (queue size=%d)", audioQueue.size()));
            }
        } catch (Exception e) {
            Log.e(TAG, "Speaker change detection error", e);
            speakerChange = null;
        }

        audioQueue.offer(chunk);
    }


    /**
     * Stops transcription and releases resources.
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
        if (speakerChange != null) {
            speakerChange.reset(true);
        }
        audioQueue.clear();
        lastNotified = "";
        lastNotifiedUnModify = "";
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
    /**
     * Resets recognizer state.
     *
     * @param fullReset if true → recreate recognizer
     */
    private synchronized void resetTranscriber(boolean fullReset) {
        if (model == null) {
            Log.w(TAG, "resetTranscriber called but model not initialized");
            return;
        }
        Log.w(TAG, "reset Transcriber called and running...");
        if (recognizer != null && !fullReset) {
            try {
                synchronized (lock) {
                    recognizer.reset();
                    lastResetWasFull = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing recognizer during reset", e);
            }
        } else {
            try {
                recognizer = new Recognizer(model, sampleRate);
                lastResetWasFull = true;
                lastSourceSentence = "";
                Log.i(TAG, "Recognizer (re)initialized successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to create Recognizer during reset", e);
                notifyError(e);
                return;
            }
        }

        if(listener!=null && !lastNotifiedUnModify.isEmpty()) {
            segStart = Math.max(segStart-((segEnd-segStart)/countUnits(lastNotifiedUnModify)), 0);
            listener.onFinalResult(new transcriptSegment(segStart, segEnd, lastNotifiedUnModify));
        }
        lastResetTime = System.currentTimeMillis();
        cutPrefixLength = 0;
        lastNotified = "";
        lastNotifiedUnModify = "";
        transcriptSubtitles.setLength(0);
        segStart = 0L;
        segEnd = segStart + StreamAudioCapturer.chunkSizeMs;
        wasFinalSeg = true;
        // Note: lastWordDetectedTime you’ll update when words arrive.
    }
    /**
     * Main recognition loop.
     */
    private void processLoop() {
        long update_current_text_length = System.currentTimeMillis();
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                long now = System.currentTimeMillis();
                if (lastWordDetectedTime > lastResetTime && now - lastWordDetectedTime >= MAX_SILENCE_MS) {
                    synchronized (this) {
                        lastSourceSentence = "";
                        resetTranscriber(false);
                        Log.i(TAG, "Auto-reset after 3s of no new words and we detected word after the last reset");
                    }
                }

                else if (!lastResetWasFull && now - lastWordDetectedTime >= MAX_SILENCE_MS * 2) {
                    synchronized (this) {
                        resetTranscriber(true);
                        Log.i(TAG, "Reset after 6s of no new words and last reset wasnt full");
                    }
                }

                else if (now - lastResetTime >= MAX_RESET_INTERVAL) {
                    resetTranscriber(true);
                    Log.i(TAG, "Auto-reset after long run");
                }

                TaggedAudioChunk chunk;
                try {
                    chunk = audioQueue.take();
                } catch (InterruptedException e) {
                    Log.i(TAG, "Transcription thread interrupted — stopping normally.");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in transcription loop", e);
                    break;
                }

                if (lastResetTime<lastWordDetectedTime&&update_current_text_length<lastWordDetectedTime&&((chunk.getResetBefore() && !lastNotified.isEmpty() && countUnits(lastNotified)>cutPrefixLength+1) || countUnits(lastNotified)>(MainPipeline.MAX_SUBTITLES_WORDS-1))) {
                    Log.i(TAG, "====================================================================================================");
                    Log.i(TAG, "==================== 🔊 SPEAKER CHANGE DETECTED → Resetting recognizer ====================");
                    Log.i(TAG, "====================================================================================================");
                    Log.i(TAG, "==================== lastNotified: " + lastNotified + " ====================");

                    if(!lastSourceSentence.equals(lastNotified)) {
                        lastSourceSentence = joinTexts(lastSourceSentence, lastNotified);
                    }
                    // 2) count the words in lastNotified
                    int count = countUnits(lastNotified);
                    cutPrefixLength += count;
                    update_current_text_length = System.currentTimeMillis();
                    Log.i(TAG, "==================== lastSourceSentence: " + lastSourceSentence + " ====================");
                    Log.i(TAG, "==================== cutPrefixLength: " + cutPrefixLength + " ====================");
                    Log.i(TAG, "====================================================================================================");
                }

                String result = "";
                boolean isFinal = false;
                synchronized (lock) {
                    if (recognizer == null) continue;
                    isFinal = recognizer.acceptWaveForm(chunk.getShortAudio(), chunk.getShortAudio().length);
                    result = isFinal ? recognizer.getResult() : recognizer.getPartialResult();
                }
                handleTexts(result, isFinal, chunk.getTime());
            }
        } catch (Exception e) {
            Log.e(TAG, "Transcription thread error", e);
            notifyError(e);
        }
    }
    /**
     * Parses Vosk JSON and updates subtitles.
     */
    private boolean handleTexts(String json, boolean finalText, long chunkTime) {
        try {
            synchronized (this) {
                JSONObject obj = new JSONObject(json);
                //JSONArray wordsArr = obj.optJSONArray("result");
                List<String> newWords = new ArrayList<>();
                if (finalText) {
                    //Log.i(TAG, "🔚 FINAL JSON: " + json);
                    JSONArray wordsArr = obj.optJSONArray("result");
                    if (wordsArr != null) {
                        for (int i = 0; i < wordsArr.length(); i++) {
                            JSONObject w = wordsArr.getJSONObject(i);
                            newWords.add(w.getString("word"));
                        }
                    } else {
                        String fallback = obj.optString("text", "").trim();
                        //Log.w(TAG, "⚠️ Vosk final result has no words array. Fallback text=" + fallback);
                        if (!fallback.isEmpty()) {
                            if (specialLanguageNotSpacedOut) {
                                newWords.add(fallback);
                            } else {
                                newWords.addAll(Arrays.asList(fallback.split("\\s+")));
                            }
                        }
                    }
                    segEnd = chunkTime;
                } else {
                    String partial = obj.optString("partial", "").trim();
                    if (!partial.isEmpty()) {
                        if (specialLanguageNotSpacedOut) {
                            newWords.add(partial);
                        } else {
                            String[] allWords = partial.split("\\s+");
                            for (int i = 0; i < allWords.length - 1; i++) {
                                newWords.add(allWords[i]);
                            }
                        }
                        if(wasFinalSeg) {
                            segStart = chunkTime;
                            segEnd = segStart + StreamAudioCapturer.chunkSizeMs;
                            wasFinalSeg = false;
                        } else {
                            segEnd = chunkTime;
                        }
                    }
                }

                if (newWords.isEmpty()) {
                    //Log.i(TAG, "No new words to display (already shown or partial too short)");
                    return false;
                }

                String text = specialLanguageNotSpacedOut
                        ? String.join("", newWords)
                        : String.join(" ", newWords);

                lastNotifiedUnModify = text;
                String textRes = removeFirstNUnits(text, cutPrefixLength);

                boolean ans = true;

                if (textRes.equals(lastNotified)) {
                    //Log.w(TAG, "No transcript detected : Same as last one");
                    ans = false;
                } else {
                    lastWordDetectedTime = System.currentTimeMillis();
                    transcriptSubtitles.replace(0, transcriptSubtitles.length(), textRes);

                    notifyListener(lastSourceSentence, transcriptSubtitles.toString());
                }

                if (finalText) {
                    lastSourceSentence = text;
                    resetTranscriber(false);
                    Log.i(TAG, "💡 reset final results");
                }

                return ans;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to handle transcript text: ", e);
            return false;
        }
    }
    /**
     * Dispatch subtitle update to UI thread.
     */
    private void notifyListener(String lastSentence, String currentText) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTranscriptionUpdate(lastSentence, currentText));
            lastNotified = currentText;
        }
    }

    /**
     * Notifies model change.
     */
    private void notifyLangChange(String newLang, String Path) {
        if (listener != null) {
            mainHandler.post(() -> listener.onModelChange(newLang, Path));
        }
    }
    /**
     * Notifies error.
     */
    private void notifyError(Exception e) {
        if (listener != null) {
            mainHandler.post(() -> listener.onError(e));
        }
    }
    /**
     * Releases everything.
     */
    public void destroy() {
        stop();
        if (speakerChange != null) {
            speakerChange.close();
        }
        switchExecutor.shutdownNow();
    }

    /**
     * Returns true if currently building a sentence.
     */
    public boolean inTheMiddle() {
        return !lastNotifiedUnModify.isEmpty();
    }

    private String joinTexts(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.isEmpty()) return b.stripLeading();
        if (b.isEmpty()) return a.stripTrailing();

        Locale locale = Locale.forLanguageTag(currentLang);
        boolean noSpaceLang = NOSPACE_LANGS.contains(locale.getLanguage());

        if (noSpaceLang) {
            return a + b;
        } else {
            boolean needsSpace = !a.endsWith(" ") && !b.startsWith(" ");
            return needsSpace ? a + " " + b : a + b;
        }
    }


    private int countUnits(String text) {
        Locale locale = Locale.forLanguageTag(currentLang);
        boolean noSpace = NOSPACE_LANGS.contains(locale.getLanguage());

        BreakIterator bi = noSpace
                ? BreakIterator.getCharacterInstance(locale)
                : BreakIterator.getWordInstance(locale);
        bi.setText(text);

        int count = 0;
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            String piece = text.substring(start, end);
            if (noSpace) {
                count++;
            } else {
                int cp = piece.codePointAt(0);
                if (Character.isLetterOrDigit(cp)) {
                    count++;
                }
            }
        }
        return count;
    }

    private String removeFirstNUnits(String text, int unitsToRemove) {
        if (text == null || text.isEmpty() || unitsToRemove <= 0) return text;

        Locale locale = Locale.forLanguageTag(currentLang);
        boolean noSpaceLang = NOSPACE_LANGS.contains(locale.getLanguage());

        BreakIterator bi = noSpaceLang
                ? BreakIterator.getCharacterInstance(locale)
                : BreakIterator.getWordInstance(locale);

        bi.setText(text);
        int start = bi.first();
        int count = 0;

        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            if (noSpaceLang) {
                count++;
            } else {
                String piece = text.substring(start, end);
                int cp = piece.codePointAt(0);
                if (Character.isLetterOrDigit(cp)) {
                    count++;
                } else {
                    continue;
                }
            }
            if (count >= unitsToRemove) {
                return text.substring(end).stripLeading();
            }
        }

        return "";
    }



    /**
     * Listener for transcription events.
     */
    public interface Listener {

        /** Called when partial subtitles update */
        void onTranscriptionUpdate(String lastSentence, String fullText);

        /** Called when a final segment is produced */
        void onFinalResult(transcriptSegment seg);

        /** Called on fatal error */
        void onError(Exception e);

        /** Called after language model change */
        void onModelChange(String newLang, String modelPath);
    }
}