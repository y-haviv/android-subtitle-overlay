package com.example.subtitles.model.speaker;

import android.content.Context;
import android.util.Log;


import com.example.subtitles.model.audio.StreamAudioCapturer;
import com.example.subtitles.util.AssetUtils;
import com.example.subtitles.view_model.transcriptManager;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * High-accuracy speaker change detector based on Pyannote embedding ONNX model.
 *
 * <p>
 * This detector converts audio windows into speaker embeddings and monitors
 * their temporal evolution. A speaker change is declared only when a new
 * embedding deviates sufficiently from the historical speaker profile.
 *
 * <p>
 * Pipeline:
 * <ol>
 *   <li>Collect 1-second circular PCM window</li>
 *   <li>Convert to float waveform</li>
 *   <li>Run Pyannote ONNX embedding model</li>
 *   <li>Update EMA embedding</li>
 *   <li>Compute cosine distance</li>
 *   <li>Apply statistical decision rule</li>
 * </ol>
 *
 * <p>
 * Additional gating:
 * <ul>
 *   <li>Adaptive RMS-based silence detection</li>
 *   <li>Embedding history statistics</li>
 * </ul>
 *
 * Thread-safe singleton.
 */
public class SpeakerChangeDetector {
    /** Desired duration of embedding history (seconds). */
    static final float DESIRED_HISTORY_DURATION_SEC = 1.5f;
    /** Number of embeddings to retain. */
    static final int HISTORY_SIZE = Math.round(DESIRED_HISTORY_DURATION_SEC * 1000f / StreamAudioCapturer.chunkSizeMs);
    private static final String TAG = "SpeakerChangePyannote";
    /** Audio sample rate (Hz). */
    private static final int SAMPLE_RATE = transcriptManager.sampleRate; // 16000
    /** Samples per audio chunk. */
    private static final int CHUNK_SAMPLES = SAMPLE_RATE * StreamAudioCapturer.chunkSizeMs / 1000;
    /** Length of analysis window in seconds. */
    private static final int WINDOW_SECONDS = 1;
    /** Samples in one analysis window. */
    private static final int WINDOW_SIZE = SAMPLE_RATE * WINDOW_SECONDS;
    /** Base cosine distance threshold. */
    private static final float THRESHOLD = 0.78f;
    /** EMA decay factor for embeddings. */
    private static final float DECAY = 0.9f;
    /** Multiplier for standard deviation test. */
    private static final float STDEV_MULT = 2.0f;
    /** ONNX model filename in app internal storage. */
    public static final String MODEL_PATH = AssetUtils.PYANNOTE_MODEL_FILE;
    private static SpeakerChangeDetector instance;
    /** Adaptive RMS silence threshold. */
    private static double RMS_THRESHOLD = SmoothRmsAdjuster.RMS_THRESHOLD_DEFAULT;
    /** ONNX runtime environment. */
    private final OrtEnvironment env;
    /** ONNX inference session. */
    private final OrtSession session;
    /** Circular PCM buffer. */
    // rolling window
    private final short[] pcmBuffer = new short[WINDOW_SIZE];
    /** Rolling history of embeddings. */
    private final Deque<float[]> embedHistory;
    /** Model input tensor name. */
    private String inputName;
    /** Circular buffer write index. */
    private int writePos = 0;

    /** Number of valid samples in buffer. */
    private int filled = 0;

    /** Exponential moving average embedding. */
    private float[] emaEmbed = null;

    /** EMA of RMS energy. */
    private double emaRms = 0.0;

    /**
     * Private constructor.
     */
    private SpeakerChangeDetector(Context ctx) throws IOException {
        this.embedHistory = new ArrayDeque<>(HISTORY_SIZE);

        try {
            env = OrtEnvironment.getEnvironment();
            File modelFile = AssetUtils.getRequiredRuntimeModelFile(ctx, MODEL_PATH);
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.addConfigEntry("session.use_xnnpack", "1");
            session = env.createSession(modelFile.getAbsolutePath(), opts);
            inputName = session.getInputNames().iterator().next();

            Log.i(TAG, "Loaded Pyannote Embedding ONNX model: " + MODEL_PATH);
        } catch (OrtException e) {
            throw new IOException("ONNX Runtime init failed", e);
        }
    }
    /**
     * Returns singleton instance.
     */
    public static SpeakerChangeDetector getInstance(Context context) {
        if (instance == null) {
            synchronized (SpeakerChangeDetector.class) {
                if (instance == null) {
                    try {
                        instance = new SpeakerChangeDetector(context);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to initialize SpeakerChangeDetector", e);
                    }
                }
            }
        }
        return instance;
    }

    /**
     * Checks if RMS indicates silence.
     */
    public static boolean isSilent(double rmsValue) {
        return rmsValue < RMS_THRESHOLD;
    }

    /**
     * Decision rule for speaker change.
     *
     * @return true if distance exceeds both absolute and statistical thresholds.
     */
    public static boolean checkHit(double dist, double mean, double std) {
        return dist > THRESHOLD && (dist - mean) > STDEV_MULT * std;
    }
    /**
     * Convert PCM shorts to normalized floats.
     */
    private float[] shortToFloat(short[] input) {
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = input[i] / 32768f;  
        }
        return output;
    }

    /**
     * Computes cosine distance between two vectors.
     *
     * @return value in [0,2]
     */
    private double cosineDistance(float[] a, float[] b) {
        // normalize both
        float[] na = normalize(a);
        float[] nb = normalize(b);
        double dot = 0;
        for (int i = 0; i < na.length; i++) {
            dot += na[i] * nb[i];
        }
        // dot is now the cosine similarity
        return 1.0 - dot;
    }
    /**
     * L2-normalize vector.
     */
    private float[] normalize(float[] v) {
        float norm = 0f;
        for (float x : v) norm += x*x;
        norm = (float)Math.sqrt(norm);
        if (norm == 0f) return Arrays.copyOf(v, v.length);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i]/norm;
        return out;
    }



    /**
     * Runs ONNX embedding inference.
     */
    public float[] getEmbedding(float[] audioWindow) {
        try {
            long[] shape = new long[]{1, audioWindow.length};
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioWindow), shape)) {
                OrtSession.Result result = session.run(Map.of(inputName, inputTensor));
                float[][] embedding = (float[][]) result.get(0).getValue();
                result.close();
                return embedding[0];
            }
        } catch (OrtException e) {
            Log.e(TAG, "❌ Failed to run inference", e);
            return null;
        }
    }
    /**
     * Main entry point.
     *
     * @return true if speaker change detected.
     */
    public synchronized boolean acceptAudio(short[] chunk) {
        // Fill circular buffer
        for (short s : chunk) {
            pcmBuffer[writePos] = s;
            writePos = (writePos + 1) % WINDOW_SIZE;
            if (filled < WINDOW_SIZE) filled++;
        }
        if (filled < WINDOW_SIZE) return false;
        // Reconstruct contiguous window
        short[] window = new short[WINDOW_SIZE];
        int tail = WINDOW_SIZE - writePos;
        System.arraycopy(pcmBuffer, writePos, window, 0, tail);
        System.arraycopy(pcmBuffer, 0, window, tail, writePos);

        float[] floatInput = shortToFloat(window);

        float[] newEmbed = null;
        boolean changeDetected = false;

        try {
            newEmbed = getEmbedding(floatInput);
            if (newEmbed == null) {
                Log.e(TAG, "ONNX inference failed....");
                return false;
            }

            if (embedHistory.size() == HISTORY_SIZE) {
                embedHistory.pollFirst();
            }
            embedHistory.addLast(newEmbed);

            if (embedHistory.size() < HISTORY_SIZE) return false;

            // RMS
            double sumSq = 0.0;
            for (short s : chunk) sumSq += s*s;
            double newRms = Math.sqrt(sumSq / (double)chunk.length);

            boolean silent = isSilent(newRms);
            double[] rmsRes = SmoothRmsAdjuster.smoothAndAdjust(emaRms, newRms, RMS_THRESHOLD);
            emaRms       = rmsRes[0];
            RMS_THRESHOLD = rmsRes[1];

            if (emaEmbed == null) {
                emaEmbed = newEmbed.clone();
                return false;
            }

            if (!silent) {
                float[] prevEma = emaEmbed.clone();
                // Update EMA
                for (int i = 0; i < emaEmbed.length; i++) {
                    emaEmbed[i] = (float) (DECAY * emaEmbed[i] + (1 - DECAY) * newEmbed[i]);
                }
                double dist = cosineDistance(prevEma, newEmbed);

                // compute history mean/std
                double[] dists = embedHistory.stream()
                        .limit(embedHistory.size() - 1)
                        .mapToDouble(h -> cosineDistance(prevEma, h))
                        .toArray();
                double mean = Arrays.stream(dists).average().orElse(0);
                double std = Math.sqrt(Arrays.stream(dists)
                        .map(x -> (x - mean) * (x - mean)).average().orElse(0));

                changeDetected = checkHit(dist, mean, std);

                if (changeDetected) {
                    this.reset(false);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "ONNX inference failed", e);
        }

        return changeDetected;
    }
    /**
     * Resets internal state.
     *
     * @param fullReset if true clears everything.
     */
    public synchronized void reset(boolean fullReset) {
        RMS_THRESHOLD = SmoothRmsAdjuster.RMS_THRESHOLD_DEFAULT;
        writePos = 0;
        filled = 0;
        if (fullReset) {
            embedHistory.clear();
            emaEmbed = null;
            emaRms = 0.0;
        } else {
            writePos = (writePos + 2) % WINDOW_SIZE;
            filled = Math.min(WINDOW_SIZE, filled + 2);

            // preserve only the two most recent history entries
            float[][] recent = embedHistory.stream()
                    .skip(Math.max(0, embedHistory.size() - 2))
                    .toArray(float[][]::new);
            embedHistory.clear();
            Arrays.stream(recent).forEach(embedHistory::addLast);
            // clear audioWindow but reinsert the last two chunks
            short[] last1 = Arrays.copyOfRange(pcmBuffer,
                    pcmBuffer.length - 2 * CHUNK_SAMPLES,
                    pcmBuffer.length - CHUNK_SAMPLES);
            short[] last2 = Arrays.copyOfRange(pcmBuffer,
                    pcmBuffer.length - CHUNK_SAMPLES,
                    pcmBuffer.length);
            java.util.Arrays.fill(pcmBuffer, (short)0);
            System.arraycopy(last1, 0, pcmBuffer,
                    pcmBuffer.length - 2 * CHUNK_SAMPLES,
                    CHUNK_SAMPLES);
            System.arraycopy(last2, 0, pcmBuffer,
                    pcmBuffer.length - CHUNK_SAMPLES,
                    CHUNK_SAMPLES);
        }

        Log.i(TAG, "SpeakerChangeDetector reset");
    }
    /**
     * Releases ONNX resources.
     */
    public synchronized void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (OrtException e) {
            Log.w(TAG, "Error while closing ONNX session", e);
        } finally {
            instance = null;
        }
    }
}
