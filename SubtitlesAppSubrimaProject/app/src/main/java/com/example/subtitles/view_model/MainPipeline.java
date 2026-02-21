package com.example.subtitles.view_model;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.subtitles.model.translation.MlKitTranslator;
import com.example.subtitles.view.overlay.SubtitleOverlayService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainPipeline is the central orchestrator for the transcription and subtitle workflow.
 *
 * Responsibilities include:
 * 1. Starting and stopping audio capture via transcriptManager.
 * 2. Handling source language detection and switching.
 * 3. Translating transcriptions to a target language using MlKitTranslator.
 * 4. Updating subtitles in the overlay service.
 * 5. Managing UI callbacks through the Listener interface.
 *
 * It combines transcription, translation, and display in a single pipeline,
 * allowing optional translation and smart handling of long subtitles.
 */
public class MainPipeline {
    /// Maximum number of words shown in subtitles at a time
    public static final int MAX_SUBTITLES_WORDS = 10;

    /// Logging tag
    private static final String TAG = "MainPipeline";

    /// Core transcription engine
    private final transcriptManager transcriber;

    /// Translator instance
    private final MlKitTranslator translator;

    /// Accumulates current transcript
    private final StringBuilder transcript = new StringBuilder();

    /// Application context
    private final Context cxt;

    /// Tracks whether the pipeline is started
    private final AtomicBoolean started = new AtomicBoolean(false);

    /// Main thread handler for UI updates and scheduling
    private final Handler handler = new Handler(Looper.getMainLooper());

    /// Pipeline listener for callbacks
    private Listener listener;

    /// Map of supported languages for Google Translate / translation validation
    private JSONObject googleLangMap;

    /// Current source language settings
    private String sourceLang = "auto"; // user-selected source language
    private String srcLang = "en";      // active detected/used source language
    private String subtitleLang = "en"; // active subtitle/translation target language

    /// Indicates whether subtitle overlay service is initialized and ready
    private boolean subtitlesServiceReady = false;

    /**
     * Runnable for resetting subtitles after a timeout.
     * Clears overlay and notifies listener with empty strings.
     */
    private final Runnable resetRunnable = () -> {
        Log.d(TAG, "Resetting subtitles due to timeout");
        // Notify listener with empty strings to reset UI and translation
        if (listener != null) {
            listener.onTranscriptionUpdate("");
            listener.onTransltionUpdate("");
        }
        // Clear the overlay as well
        if(subtitlesServiceReady) {
            SubtitleOverlayService.updateText("");
        }
        };


    /**
     * Constructs the MainPipeline instance.
     * Initializes translator, loads language map, and sets up the transcriptManager listener.
     *
     * @param context Application context
     * @throws IOException if loading google_dict.json fails
     */
    public MainPipeline(Context context) throws IOException {
        this.cxt = context;
        // Initialize translator
        MlKitTranslator tempTransltor = null;
        try {
            tempTransltor = new MlKitTranslator(context, srcLang, subtitleLang);
        } catch (Exception e) {
            Log.e(TAG, "Translator initialization failed", e);
            notifyError(e.getMessage());
        }
        translator = tempTransltor;
        // Load Google language map from assets
        try {
            InputStream is = context.getAssets().open("google_dict.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, "UTF-8");
            googleLangMap = new JSONObject(json);
            Log.d(TAG, "Google language map loaded with " + googleLangMap.length() + " entries");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load google_dict.json", e);
            googleLangMap = new JSONObject(); // fail-safe fallback
        }
        // Initialize transcript manager singleton
        this.transcriber = transcriptManager.getInstance(context, srcLang, googleLangMap);



        // Set listener for transcription events
        this.transcriber.setListener(new transcriptManager.Listener() {
            @Override
            public void onTranscriptionUpdate(String lastSourceSentence, String fullText) {
                // Cancel any pending reset operations
                handler.removeCallbacks(resetRunnable);

                // Update internal transcript buffer
                transcript.setLength(0);
                transcript.append(fullText);
                // Notify listener
                if (listener != null) {
                    listener.onTranscriptionUpdate(lastSourceSentence + "\n|||||||\n" + fullText);
                }
                // Update subtitle overlay based on translation availability
                if (fullText.isEmpty()) {
                    if(subtitlesServiceReady) {
                        SubtitleOverlayService.updateText(fullText);
                    }
                } else if (translator != null && !srcLang.equals(subtitleLang)) {
                    // Translation is enabled and needed
                    if (!translator.isReady()) {
                        //Log.d(TAG, "got transcript but translate is not ready");
                        if (listener != null) {
                            listener.onTransltionUpdate("Loading translation…");
                        }
                    } else {
                        try {
                            translator.translate(lastSourceSentence, fullText, new MlKitTranslator.TranslationCallback() {
                                @Override
                                public void onResult(String fullTranslated, String translated) {
                                    String displayText = trimToLastNUnits(translated, subtitleLang, MAX_SUBTITLES_WORDS);

                                    // Update overlay
                                    if (!translated.isEmpty() && subtitlesServiceReady) {
                                        SubtitleOverlayService.updateText(displayText);
                                    }
                                    if (listener != null) {
                                        listener.onTransltionUpdate(fullTranslated);
                                    }
                                }

                                @Override
                                public void onError(MlKitTranslator.TranslationException e) {
                                    Log.e("Pipeline", "Translation error", e);
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Translator translate failed: ", e);

                        }
                    }
                } else {
                    // No translation needed
                    String displayText = trimToLastNUnits(fullText, subtitleLang, MAX_SUBTITLES_WORDS);

                    Log.d("Pipeline", "NO Translated - Just Transcript: " + displayText);
                    if (!displayText.isEmpty() && subtitlesServiceReady) {
                        SubtitleOverlayService.updateText(displayText);
                    }
                    if (listener != null) {
                        listener.onTransltionUpdate("No translation needed");
                    }
                }
                // Schedule reset after 2 seconds
                handler.postDelayed(resetRunnable, 2000);
            }

            @Override
            public void onError(String e) {
                notifyError(e);
            }

            @Override
            public void onModelTranscriptChange(String newLang) {
                if (!newLang.isEmpty() && (!newLang.equals(srcLang))) {
                    srcLang = newLang;
                    setLanguage(subtitleLang);
                }
            }

            @Override
            public void onLanguageDetected(String lang) {
                if (listener != null) {
                    listener.onLanguageDetected("Language: " + lang + " : " + lang + "(X)");
                }
            }

            @Override
            public void onLanguageChange(String lang) {
                if (listener != null) {
                    listener.onLanguageDetected("Language: " + lang + " : " + lang + "(V)");
                }
            }

        });


    }
    /**
     * Trims text to the last N word units, to prevent overly long subtitles.
     *
     * @param text     The original text
     * @param langTag  Language tag for proper word segmentation
     * @param maxUnits Maximum number of words to retain
     * @return Trimmed text
     */
    private String trimToLastNUnits(String text, String langTag, int maxUnits) {
        Locale locale = Locale.forLanguageTag(langTag);
        BreakIterator bi = BreakIterator.getWordInstance(locale);
        bi.setText(text);

        List<Integer> boundaries = new ArrayList<>();
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            String piece = text.substring(start, end);
            if (Character.isLetterOrDigit(piece.codePointAt(0))) {
                boundaries.add(start);
            }
        }
        boundaries.add(text.length());

        int total = boundaries.size() - 1;
        if (total <= maxUnits) {
            return text;
        }
        int cutIndex = boundaries.get(total - maxUnits);
        return text.substring(cutIndex).trim();
    }
    /**
     * Updates pipeline parameters from SharedPreferences
     */
    public void setParmeters() {
        if (!started.get()) {
            Log.i(TAG, "pipeline not working (running) yet there is no need to do it now...");
            return;
        }
        SharedPreferences prefs = cxt.getSharedPreferences("subrima_prefs", Context.MODE_PRIVATE);
        sourceLang   = prefs.getString("pref_source_lang",   "auto");
        if(!sourceLang.equals("auto")&&!sourceLang.equals(srcLang)) {
            srcLang = sourceLang;
        }
        if(!subtitleLang.equals(prefs.getString("pref_subtitle_lang", "en"))) {
            if(!setLanguage(prefs.getString("pref_subtitle_lang", "en"))) {
                notifyError("problem changing subtitles lang...");
            }
        }
        transcriber.setParmeters();
    }


    /**
     * Sets the pipeline listener for UI updates or error handling
     */
    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * Starts audio capture, transcription, translation, and overlay.
     *
     * @return true if audio capture started successfully
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public synchronized boolean start() {
        if (!started.compareAndSet(false, true)) {
            Log.i(TAG, "Already translating");
            return false;
            //throw new IllegalStateException("Already translating");
        }
        setParmeters();
        boolean started = transcriber.start();
        if (started) {
            if (translator != null) {
                translator.resume(new MlKitTranslator.ReadyListener() {
                    @Override
                    public void onReady() {
                        Log.i(TAG, "Translator models are ready after resume");
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Translator failed to resume", e);
                        notifyError(e.getMessage());
                    }
                });
            }
            // Start subtitle overlay service
            Intent overlayIntent = new Intent(cxt, SubtitleOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cxt.startForegroundService(overlayIntent);
            } else {
                cxt.startService(overlayIntent);
            }
            SubtitleOverlayService.showOverlay();
            subtitlesServiceReady = true;
        } else {
            Log.w(TAG, "Audio capture failed to start");
        }
        return started;
    }

    /**
     * Stops audio capture, transcription, translation, and overlay.
     */
    public synchronized void stop() {
        if (!started.get()) return;
        handler.removeCallbacks(resetRunnable);
        SubtitleOverlayService.hideOverlay();
        subtitlesServiceReady = false;
        transcriber.stop();
        if (translator != null) {
            translator.pause();
        }
        if(srcLang.isEmpty()) {
            srcLang = "en";
        }
        started.set(false);
    }

    /**
     * Releases all resources and stops services. Call in Activity.onDestroy().
     */
    public void destroy() {
        stop();
        cxt.stopService(new Intent(cxt, SubtitleOverlayService.class));
        transcriber.close();
        if (translator != null) {
            try {
                translator.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing Translator", e);
            }
        }
        Log.i(TAG, "Pipeline destroyed");
    }
    /**
     * Helper to notify the listener of an error
     */
    private void notifyError(String e) {
        if (listener != null) listener.onError(e);
    }

    /**
     * Switches the subtitle translation language at runtime.
     *
     * @param newDstLang Target language
     * @return true if language switch succeeded
     */
    public boolean setLanguage(String newDstLang) {
        try {
            if (translator != null) {
                Log.d(TAG, "Requesting to switch translation language to: " + srcLang + "->" + newDstLang);
                boolean success = translator.setLanguages(srcLang, newDstLang);
                if (success) {
                    subtitleLang = newDstLang;
                    Log.i(TAG, "Successfully switched target language to: " + newDstLang);
                } else {
                    Log.w(TAG, "Failed to switch target language to: " + newDstLang);
                }
                return success;
            } else {
                Log.w(TAG, "Translator instance is null – cannot switch language");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while switching target language", e);
            return false;
        }
    }
    /**
     * Listener interface for pipeline events
     */
    public interface Listener {
        void onLanguageDetected(String lang);

        void onTranscriptionUpdate(String fullText);

        void onTransltionUpdate(String translate);

        void onError(String e);
    }

}

