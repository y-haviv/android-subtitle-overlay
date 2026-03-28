package com.example.subtitles.model.language;

import android.content.Context;
import android.util.Log;

import com.example.subtitles.util.AssetUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;

/**
 * Language Identification (LID) engine based on Silero ONNX model.
 *
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Load Silero language classifier model</li>
 *     <li>Maintain sliding audio window using circular buffer</li>
 *     <li>Run ONNX inference periodically</li>
 *     <li>Stabilize predictions using candidate confirmation logic</li>
 * </ul>
 *
 * <p>
 * Detection strategy:
 * <ul>
 *     <li>10-second window</li>
 *     <li>Stride of 4 seconds</li>
 *     <li>Language switches only after 2 consecutive identical detections</li>
 *     <li>Low-confidence predictions are ignored</li>
 * </ul>
 *
 * Thread-safe:
 * - Audio buffer operations guarded by {@code bufferLock}
 * - Language state guarded by {@code langLock}
 */
public class SileroLanguageDetector implements AutoCloseable {
    private static final String TAG = "SileroLangDetector";
    /**
     * Language dictionary asset name.
     *
     * Note: The ONNX model itself is now loaded from app internal storage
     * (context.getFilesDir()) after runtime download.
     */
    public static final String DICT_NAME = "lang_dict_95.json";
    /** ONNX Runtime environment and session */
    private final OrtEnvironment env;
    private final OrtSession session;

    /** Lock guarding language state */
    private final Object langLock = new Object();

    /** Sliding window configuration (milliseconds) */
    private static final int WINDOW_MS = 10000;
    private static final int STRIDE_MS = 4000;
    /** Derived window sizes in samples */
    private final int windowSamples;
    private final int strideSamples;
    /** Circular audio buffer */
    private final float[] circularBuffer;
    private int writePos = 0;
    private long samplesSinceLastDetect = 0;
    /** Lock guarding buffer access */
    private final Object bufferLock = new Object();

    /** Candidate stabilization */
    private String lastCandidateLang = null;
    private int candidateCount = 0;

    /** Silence detection threshold (RMS) */
    private static final float SILENCE_THRESHOLD = 1e-3f;
    /** Mapping from model index -> language code (e.g. "en") */
    private final Map<Integer,String> indexToCode;
    /** Mapping from language code -> human readable name */
    private final Map<String,String> codeToName;

    /**
     * Constructs a SileroLanguageDetector.
     *
     * @param ctx        Android context
     * @param sampleRate Audio sample rate (e.g. 16000 Hz)
     */
    public SileroLanguageDetector(Context ctx, int sampleRate)
            throws IOException, OrtException, JSONException {
        Log.i(TAG, "SileroLanguageDetector ctor: start");
        // Convert time-based window sizes into sample counts
        this.windowSamples = (WINDOW_MS * sampleRate) / 1000;
        this.strideSamples = (STRIDE_MS * sampleRate) / 1000;
        // Allocate circular buffer
        this.circularBuffer = new float[windowSamples];

        // Initialize ONNX runtime
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        File modelFile = AssetUtils.getRequiredRuntimeModelFile(ctx, AssetUtils.SILERO_MODEL_FILE);
        session = env.createSession(modelFile.getAbsolutePath(), opts);
        // Load language dictionary
        JSONObject dict = AssetUtils.loadJsonObject(ctx, DICT_NAME);
        if (dict == null) {
            Log.e(TAG, "DICT JSON is null!");
        } else {
            Log.i(TAG, "Loaded DICT JSON with " + dict.length() + " entries");
        }


        indexToCode = new HashMap<>();
        codeToName  = new HashMap<>();

        Iterator<String> keys = dict.keys();
        while(keys.hasNext()) {
            String key = keys.next();        // model output index
            String[] parts = dict.getString(key).split("\\s*,\\s*", 2);
            String code = parts[0];          // e.g. "en"
            String name = parts.length>1 ? parts[1] : code;
            int idx = Integer.parseInt(key);

            indexToCode.put(idx, code);
            codeToName .put(code, name);
        }
        Log.i(TAG, "indexToCode size=" + indexToCode.size()
                + "  codeToName size=" + codeToName.size());

    }

    /**
     * Feeds normalized PCM samples and triggers detection when stride is reached.
     *
     * @param pcmFloats  Audio samples in range [-1,1]
     * @param currentLang Current language code
     * @return Possibly updated language code
     */
    public String acceptChunk(float[] pcmFloats, String currentLang) {
        synchronized (bufferLock) {
            // Write samples into circular buffer
            for (float sample : pcmFloats) {
                circularBuffer[writePos++] = sample;
                if (writePos >= windowSamples) writePos = 0;
            }
            samplesSinceLastDetect += pcmFloats.length;
            // Run detection every stride
            if (samplesSinceLastDetect >= strideSamples) {
                samplesSinceLastDetect = 0;

                // Reconstruct window in chronological order
                float[] window = new float[windowSamples];
                int tailLen = windowSamples - writePos;
                System.arraycopy(circularBuffer, writePos, window, 0, tailLen);
                System.arraycopy(circularBuffer, 0, window, tailLen, writePos);

                currentLang = runDetectOnce(window, currentLang);
            }
            return currentLang;
        }
    }
    /**
     * Computes softmax probability for the winning logit.
     */
    private float softmaxConfidence(float[] logits, int argmax) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) if (v > max) max = v;

        double sum = 0;
        for (float v : logits) sum += Math.exp(v - max);

        return (float) (Math.exp(logits[argmax] - max) / sum);
    }



    /**
     * Runs a single ONNX inference and applies stabilization logic.
     */
    private String runDetectOnce(float[] buffer, String currentLang) {
        Log.i(TAG, "runDetectOnce: invoking ONNX on buffer len=" + buffer.length);
        String detected;
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(buffer),
                new long[]{1, buffer.length})) {
            Result result = session.run(Collections.singletonMap("input", input));
            float[][] logits = (float[][]) result.get(0).getValue();
            int index = argmax(logits[0]);
            Log.i(TAG, "runDetectOnce: argmax=" + index);
            detected = indexToCode.get(index);
            Log.i(TAG, "runDetectOnce: mapped index to code=" + detected);
            if (detected == null) {
                // out of range or unmapped index → reset candidate logic
                Log.e(TAG, "lang is NULL");
                synchronized(langLock) { candidateCount = 0; }
                return currentLang;
            }

            float confidence = softmaxConfidence(logits[0], index);
            if (confidence < 0.4f) {
                Log.d(TAG, "Low confidence: " + confidence + " for " + detected);
                synchronized (langLock) {
                    candidateCount = 0; // reset if out of bounds
                }
                return currentLang;
            }

        } catch (Exception e) {
            Log.w(TAG, "Detection failed, keeping " + currentLang, e);
            synchronized (langLock) {
                candidateCount = 0; // reset if out of bounds
            }
            return currentLang;
        }
        // Stabilization logic
        synchronized (langLock) {
            if (detected.equals(lastCandidateLang)) {
                candidateCount++;
            } else {
                lastCandidateLang = detected;
                candidateCount = 1;
            }
            if (candidateCount >= 2 && !currentLang.equals(detected)) {
                Log.i(TAG, "Language switched: " + currentLang + " -> " + detected);
                candidateCount = 0; // reset after switch
                lastCandidateLang = null; // reset candidate
                currentLang = detected;
            }
        }
        return currentLang;
    }

    /**
     * RMS-based silence detection over current buffer.
     */
    private boolean isSilent() {
        double sumSq = 0;
        for (float v : circularBuffer) sumSq += v * v;
        double rms = Math.sqrt(sumSq / circularBuffer.length);
        return rms < SILENCE_THRESHOLD;
    }
    /**
     * Returns index of maximum element.
     */
    private int argmax(float[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) if (arr[i] > arr[idx]) idx = i;
        return idx;
    }

    /**
     * Clears buffers and resets candidate state.
     */
    public void stop() {
        synchronized (bufferLock) {
            Arrays.fill(circularBuffer, 0f);
            samplesSinceLastDetect = 0;
        }
        synchronized (langLock) {
            candidateCount = 0;
            lastCandidateLang = null;
        }
    }
    /**
     * Converts language code to human-readable name.
     */
    public String getCurrentLangName(String currentLang) {
        if(currentLang==null || currentLang.isEmpty() || !codeToName.containsKey(currentLang)) {
            return "";
        }
        synchronized (langLock) {
            return codeToName.get(currentLang);
        }
    }

    /**
     * Releases ONNX resources.
     */
    @Override
    public void close() {
        stop();
        try {
            session.close();
        } catch (OrtException e) {
            Log.w(TAG, "Error closing session", e);
        }
        env.close();
    }
}
