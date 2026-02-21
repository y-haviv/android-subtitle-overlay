package com.example.subtitles.model.translation;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ================================================================
 * MlKitTranslator
 * ================================================================
 *
 * Provides robust translation services using Google ML Kit's on-device
 * translation API. Supports:
 *  • Direct source->target translation
 *  • Fallback via English when direct translation fails
 *  • Thread-safe, cancelable translation requests
 *  • Sentence- and word-level extraction for incremental updates
 *
 * This class is AutoCloseable; call close() to release resources.
 */
public class MlKitTranslator implements AutoCloseable {
    private static final String TAG = "MlKitTranslator";
    // ================================================================
    // Core state
    // ================================================================
    private final Context ctx;                     // Application context
    private final ReentrantLock lock = new ReentrantLock(); // Thread safety

    private Translator directTranslator;           // Direct translation: source -> target
    private volatile boolean directReady = false;  // Ready flag for direct translator

    private Translator interTranslator;            // Intermediate translation: source -> English
    private volatile boolean interReady = false;   // Ready flag for intermediate translator

    private volatile boolean finalReady = false;   // Readiness for English->target step
    private volatile boolean useIntermediate = false; // Flag indicating fallback via English

    private String sourceLang;                     // Current source language
    private String targetLang;                     // Current target language

    private final AtomicInteger requestCounter = new AtomicInteger(0); // Tracks latest request ID

    // ================================================================
    // Listener for model readiness
    // ================================================================
    public interface ReadyListener {
        void onReady();       // Called when model is ready for use
        void onError(Exception e); // Called on any initialization error
    }

    // ================================================================
    // Constructor
    // ================================================================
    /**
     * Initialize translator with given source and target languages.
     * Begins model download/initialization asynchronously.
     *
     * @param context    Application context
     * @param sourceLang ISO language tag for source
     * @param targetLang ISO language tag for target
     */
    public MlKitTranslator(@NonNull Context context,
                           @NonNull String sourceLang,
                           @NonNull String targetLang) {
        this.ctx = context.getApplicationContext();
        this.sourceLang = sourceLang;
        this.targetLang = targetLang;
        try {
            initModelsWithCallback();
        } catch (Exception e) {
            Log.e(TAG, "Initialization error", e);
            // Model not ready; flags remain false
        }
    }
    // ================================================================
    // Initialization helpers
    // ================================================================
    /**
     * Initialize models with default logging listener.
     */
    private void initModelsWithCallback() {
        initModelsWithCallback(new ReadyListener() {
            @Override
            public void onReady() {
                Log.d(TAG, "Translator is ready (default listener)");
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Translator error (default listener)", e);
            }
        });
    }

    /**
     * Initialize translation models, with fallback logic.
     * If direct translation fails, attempts source->English->target.
     *
     * @param listener Callback when models are ready or error occurs
     */
    private void initModelsWithCallback(ReadyListener listener) {
        lock.lock();
        try {
            requestCounter.incrementAndGet(); // Cancel any in-flight requests
            directReady = interReady = finalReady = false;
            useIntermediate = false;

            closeTranslators();

            String src = safeLanguage(sourceLang);
            String tgt = safeLanguage(targetLang);
            // Build direct translator
            TranslatorOptions directOpts = new TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build();
            directTranslator = Translation.getClient(directOpts);
            // Attempt to download model
            directTranslator.downloadModelIfNeeded()
                    .addOnSuccessListener(v -> {
                        directReady = true;
                        listener.onReady();
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Direct model failed, attempting fallback via English", e);
                        setupIntermediate(new ReadyListener() {
                            @Override
                            public void onReady() {
                                listener.onReady(); // fallback succeeded
                            }

                            @Override
                            public void onError(Exception fallbackError) {
                                // both direct and fallback failed
                                listener.onError(new TranslationException.DownloadError(e));
                            }
                        });
                    });

        } finally {
            lock.unlock();
        }
    }
    /**
     * Setup intermediate translation path: source->English->target.
     * Called when direct translation is not possible.
     *
     * @param listener Callback for readiness or errors
     */
    private void setupIntermediate(ReadyListener listener) {
        lock.lock();
        try {
            String eng = TranslateLanguage.ENGLISH;

            String src = safeLanguage(sourceLang);
            TranslatorOptions interOpts = new TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(eng)
                    .build();
            interTranslator = Translation.getClient(interOpts);

            interTranslator.downloadModelIfNeeded()
                    .addOnSuccessListener(v -> {
                        interReady = true;
                        useIntermediate = true;
                        // Prepare English->target final step if needed
                        if (!eng.equals(safeLanguage(targetLang))) {
                            TranslatorOptions finalOpts = new TranslatorOptions.Builder()
                                    .setSourceLanguage(eng)
                                    .setTargetLanguage(safeLanguage(targetLang))
                                    .build();
                            directTranslator = Translation.getClient(finalOpts);

                            directTranslator.downloadModelIfNeeded()
                                    .addOnSuccessListener(v2 -> {
                                        finalReady = true;
                                        listener.onReady();
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.e(TAG, "Final fallback model (en->target) failed", e);
                                        listener.onError(new TranslationException.NoTranslationPath());
                                    });

                        } else {
                            finalReady = true;
                            listener.onReady();
                        }
                    })
                    .addOnFailureListener(e2 -> {
                        Log.e(TAG, "Intermediate model (source->en) failed", e2);
                        listener.onError(new TranslationException.NoTranslationPath());
                    });

        } finally {
            lock.unlock();
        }
    }

    // ================================================================
    // Language safety & getters
    // ================================================================
    /**
     * Validate language tag, fallback to English if invalid.
     */
    private String safeLanguage(@NonNull String tag) {
        try {
            return TranslateLanguage.fromLanguageTag(tag);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid language tag: " + tag + ", falling back to English", e);
            return TranslateLanguage.ENGLISH;
        }
    }
    /**
     * Check if the translator is fully ready.
     */
    public boolean isReady() {
        if (!useIntermediate) return directReady;
        return interReady && finalReady;
    }
    /**
     * Update source/target languages, reinitializing models if needed.
     *
     * @return true if languages were changed
     */
    public boolean setLanguages(@NonNull String newSource, @NonNull String newTarget) {
        lock.lock();
        try {
            Set<String> supported = new HashSet<>(TranslateLanguage.getAllLanguages());
            if (!supported.contains(newTarget) || !supported.contains(newSource)) {
                Log.w(TAG, "Unsupported target language: " + newTarget + " || " + newSource);
                return false;
            }

            boolean someLangChange = false;
            if (!newSource.equals(sourceLang)) {
                sourceLang = newSource;
                someLangChange = true;
            }
            if (!newTarget.equals(targetLang)) {
                targetLang = newTarget;
                someLangChange = true;
            }

            if (someLangChange) {
                // Rebuild models for new languages
                initModelsWithCallback();
            }

            return someLangChange;
        } catch (Exception e) {
            Log.e(TAG, "Error setting languages", e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    // ================================================================
    // Translation logic
    // ================================================================
    /**
     * Translate given text. Supports incremental updates and
     * automatic handling of fallback via English if needed.
     *
     * @param lastSourceSentence Previous source sentence (for context)
     * @param text Current text to translate
     * @param callback Callback for translation results
     */
    public void translate(String lastSourceSentence, @NonNull String text, @NonNull TranslationCallback callback) {
        final int myId = requestCounter.incrementAndGet();
        try {
            if (!isReady()) {
                callback.onError(new TranslationException.NotReady());
                return;
            }
            Log.d(TAG, "*************************************************************************************");
            Log.d(TAG, "lastSourceSentence: " + lastSourceSentence);
            Log.d(TAG, "text: " + text);

            //String input = lastSourceSentence.isEmpty()
             //       ? text.trim()
               //     : lastSourceSentence.trim() + "\n" + text.trim();

            // Build input string combining previous sentence with new text
            String input = lastSourceSentence.isEmpty()
                    ? text.trim()
                    : lastSourceSentence.trim() + "\n" + text.trim();

            Log.d(TAG, "input: " + input);
            // Direct translation path
            if (!useIntermediate) {
                directTranslator.translate(input)
                        .addOnSuccessListener(res -> {
                            if (requestCounter.get() != myId) return;
                            if (requestCounter.get() == myId) {
                                String onlyNew = extractNewPortion(
                                        res,
                                        text,
                                        sourceLang,
                                        targetLang
                                );
                                callback.onResult(res, onlyNew);
                            }
                        })
                        .addOnFailureListener(e -> {
                            if (requestCounter.get() == myId)
                                callback.onError(new TranslationException.ServiceError(e));
                        });

            } else {
                // Intermediate translation: source->English->target
                interTranslator.translate(input)
                        .addOnSuccessListener(interRes -> {
                            if (requestCounter.get() != myId) return;
                            if (TranslateLanguage.ENGLISH.equals(safeLanguage(targetLang))) {
                                String onlyNew = extractNewPortion(
                                        interRes,
                                        text,
                                        sourceLang,
                                        targetLang
                                );
                                callback.onResult(interRes, onlyNew);
                            } else {
                                directTranslator.translate(interRes)
                                        .addOnSuccessListener(finalRes -> {
                                            if (requestCounter.get() == myId) {
                                                String onlyNew = extractNewPortion(
                                                        finalRes,
                                                        text,
                                                        sourceLang,
                                                        targetLang
                                                );
                                                callback.onResult(finalRes, onlyNew);
                                            }
                                        })
                                        .addOnFailureListener(e2 -> {
                                            if (requestCounter.get() == myId)
                                                callback.onError(new TranslationException.ServiceError(e2));
                                        });
                            }
                        })
                        .addOnFailureListener(e -> {
                            if (requestCounter.get() == myId)
                                callback.onError(new TranslationException.ServiceError(e));
                        });
            }
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected error in translate()", ex);
            callback.onError(new TranslationException.ServiceError(ex));
        }
    }
    // ================================================================
    // Text utility methods (sentence/word splitting)
    // ================================================================
    private int countSentences(String text, Locale locale) {
        BreakIterator bi = BreakIterator.getSentenceInstance(locale);
        bi.setText(text.trim());
        int count = 0, start = bi.first(), end = bi.next();
        while (end != BreakIterator.DONE) {
            if (!text.substring(start, end).trim().isEmpty()) count++;
            start = end; end = bi.next();
        }
        return count;
    }

    private int countWords(String text, Locale locale) {
        BreakIterator bi = BreakIterator.getWordInstance(locale);
        bi.setText(text.trim());
        int count = 0, start = bi.first(), end = bi.next();
        while (end != BreakIterator.DONE) {
            String word = text.substring(start, end).trim();
            if (!word.isEmpty() && Character.isLetterOrDigit(word.codePointAt(0))) {
                count++;
            }
            start = end;
            end = bi.next();
        }
        return Math.max(1, count);
    }


    private List<String> splitBySentences(String text, Locale locale) {
        BreakIterator bi = BreakIterator.getSentenceInstance(locale);
        bi.setText(text.trim());
        List<String> list = new ArrayList<>();
        int start = bi.first(), end = bi.next();
        while (end != BreakIterator.DONE) {
            String s = text.substring(start, end).trim();
            if (!s.isEmpty()) list.add(s);
            start = end; end = bi.next();
        }
        return list;
    }

    private List<String> splitByWords(String text, Locale locale) {
        BreakIterator bi = BreakIterator.getWordInstance(locale);
        bi.setText(text.trim());
        List<String> list = new ArrayList<>();
        int start = bi.first(), end = bi.next();
        while (end != BreakIterator.DONE) {
            String w = text.substring(start, end).trim();
            if (!w.isEmpty() && Character.isLetterOrDigit(w.codePointAt(0))) {
                list.add(w);
            }
            start = end;
            end = bi.next();
        }
        return list;
    }

    /**
     * Extracts the portion of translated text corresponding to new input.
     * Uses sentence or word boundaries for precision.
     */
    private String extractNewPortion(String fullTranslated,
                                     String newText,
                                     String sourceLangTag,
                                     String targetLangTag) {
        if (fullTranslated == null || fullTranslated.trim().isEmpty()) {
            Log.i(TAG, "✅ extractLastSentenceByBoundary returning: ''");
            Log.i(TAG, "🔤 FULL TRANSLATION:\n" + fullTranslated);
            Log.d(TAG, "*************************************************************************************");
            return "";
        }

        Locale srcLocale = Locale.forLanguageTag(sourceLangTag);
        Locale tgtLocale = Locale.forLanguageTag(targetLangTag);

        int sentenceCount = countSentences(newText, srcLocale);
        String subtitles = "";
        if (sentenceCount > 1) {
            List<String> sentences = splitBySentences(fullTranslated, tgtLocale);
            int from = Math.max(0, sentences.size() - sentenceCount);
            subtitles = String.join(" ", sentences.subList(from, sentences.size()));
        } else {
            int wordCount = countWords(newText, srcLocale);
            List<String> words = splitByWords(fullTranslated, tgtLocale);
            int from = Math.max(0, words.size() - wordCount);
            subtitles = String.join(" ", words.subList(from, words.size()));
        }

        Log.i(TAG, "✅ extractLastSentenceByBoundary returning: '" + subtitles + "'");
        Log.i(TAG, "🔤 FULL TRANSLATION:\n" + fullTranslated);
        Log.d(TAG, "*************************************************************************************");

        return subtitles;
    }

    // ================================================================
    // Pause/resume/close
    // ================================================================
    /** Pause translation, increment request counter and close current models */
    public void pause() {
        lock.lock();
        try {
            requestCounter.incrementAndGet();
            closeTranslators();
            directReady = interReady = finalReady = false;
            Log.d(TAG, "Translator paused");
        } catch (Exception e) {
            Log.e(TAG, "Error during pause()", e);
        } finally {
            lock.unlock();
        }
    }
    /** Resume translation by reinitializing models asynchronously */
    public void resume(ReadyListener listener) {
        new Thread(() -> {
            try {
                initModelsWithCallback(listener);
                Log.d(TAG, "Translator resume started");
            } catch (Exception e) {
                Log.e(TAG, "Error during resume()", e);
                listener.onError(e);
            }
        }).start();
    }

    /** Close translators and release resources */
    @Override
    public void close() {
        lock.lock();
        try {
            requestCounter.incrementAndGet();
            closeTranslators();
        } catch (Exception e) {
            Log.e(TAG, "Error during close()", e);
        } finally {
            lock.unlock();
        }
    }
    /** Close both direct and intermediate translators */
    private void closeTranslators() {
        try {
            if (directTranslator != null) {
                directTranslator.close();
                directTranslator = null;
            }
            if (interTranslator != null) {
                interTranslator.close();
                interTranslator = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing translators", e);
        }
    }

    // ================================================================
    // Translation callback interface
    // ================================================================
    public interface TranslationCallback {
        void onResult(String fullTranslated, @NonNull String translated);

        void onError(@NonNull TranslationException e);
    }

    // ================================================================
    // Custom exceptions
    // ================================================================
    public static abstract class TranslationException extends Exception {
        public TranslationException(String msg) {
            super(msg);
        }

        public static class NotReady extends TranslationException {
            public NotReady() {
                super("Models not ready");
            }
        }

        public static class ServiceError extends TranslationException {
            public ServiceError(Throwable cause) {
                super("Service error: " + cause.getMessage());
                initCause(cause);
            }
        }

        public static class NoTranslationPath extends TranslationException {
            public NoTranslationPath() {
                super("No available translation path (direct or via English)");
            }
        }

        public static class DownloadError extends TranslationException {
            public DownloadError(Throwable cause) {
                super("Model download failed: " + cause.getMessage());
                initCause(cause);
            }
        }


    }
}
