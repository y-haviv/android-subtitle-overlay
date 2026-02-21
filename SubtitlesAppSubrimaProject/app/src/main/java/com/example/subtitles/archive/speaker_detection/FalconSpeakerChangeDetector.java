package com.example.subtitles.archive.speaker_detection;

import android.content.Context;
import android.util.Log;

import ai.picovoice.falcon.Falcon;
import ai.picovoice.falcon.FalconException;
import ai.picovoice.falcon.FalconSegment;

/**
 * Lightweight speaker-change detector based on Picovoice Falcon.
 *
 * <p>
 * Uses a sliding window of PCM samples and invokes Falcon diarization.
 * Detects a speaker change only when the current speaker tag differs
 * from the previously observed one.
 *
 * <p>
 * Implemented as a thread-safe singleton.
 */
public class FalconSpeakerChangeDetector {
    private static final String TAG = "FalconDetector";
    /** Falcon requires at least 512 samples per inference */
    private static final int WINDOW_SIZE = 512;
    private static FalconSpeakerChangeDetector instance;
    /** Picovoice Falcon engine */
    private final Falcon falcon;
    /** Sliding PCM window */
    private final short[] slidingWindow = new short[WINDOW_SIZE];
    /** Number of valid samples currently inside the window */
    private int filledSamples = 0;
    /** Last detected speaker identifier */
    private int lastSpeakerId = -1;
    /**
     * Private constructor (singleton).
     */
    private FalconSpeakerChangeDetector(Context context) throws FalconException {
        falcon = new Falcon.Builder()
                .setAccessKey("YOUR_FALCON_ACCESS_KEY")
                .build(context);
    }
    /**
     * Returns the singleton instance.
     */
    public static FalconSpeakerChangeDetector getInstance(Context context) {
        if (instance == null) {
            synchronized (FalconSpeakerChangeDetector.class) {
                if (instance == null) {
                    try {
                        instance = new FalconSpeakerChangeDetector(context);
                    } catch (FalconException e) {
                        Log.e(TAG, "Failed to initialize FalconSpeakerChangeDetector", e);
                    }
                }
            }
        }
        return instance;
    }

    /**
     * Feeds new PCM samples and checks for speaker change.
     *
     * @param pcm New PCM samples
     * @return true if a real speaker change was detected
     */
    public synchronized boolean process(short[] pcm) {
        if (falcon == null) return false;

        int step = pcm.length;

        // If window not yet full, keep filling it
        if (filledSamples < WINDOW_SIZE) {
            int toCopy = Math.min(step, WINDOW_SIZE - filledSamples);
            System.arraycopy(pcm, 0, slidingWindow, filledSamples, toCopy);
            filledSamples += toCopy;
            // Still not enough data
            if (filledSamples < WINDOW_SIZE) {
                return false;
            }
            // If extra samples remain, process them recursively
            if (step > toCopy) {
                short[] remainder = new short[step - toCopy];
                System.arraycopy(pcm, toCopy, remainder, 0, remainder.length);
                return process(remainder);
            }
        } else {
            // Window is full → slide forward
            System.arraycopy(
                    slidingWindow,
                    step,
                    slidingWindow,
                    0,
                    WINDOW_SIZE - step
            );
            System.arraycopy(
                    pcm,
                    0,
                    slidingWindow,
                    WINDOW_SIZE - step,
                    step
            );
        }

        // Run Falcon on the full window
        try {
            FalconSegment[] segments = falcon.process(slidingWindow);
            if (segments == null || segments.length == 0) {
                return false;
            }

            int currentSpeakerId = segments[segments.length - 1].getSpeakerTag();
            // First ever detection
            if (lastSpeakerId == -1) {
                lastSpeakerId = currentSpeakerId;
                return false;
            }
            // Speaker changed
            if (currentSpeakerId != lastSpeakerId) {
                Log.i(TAG, "Detected speaker change from "
                        + lastSpeakerId + " to " + currentSpeakerId);
                lastSpeakerId = currentSpeakerId;
                return true;
            }
            return false;
        } catch (FalconException e) {
            Log.e(TAG, "Falcon processing failed", e);
            return false;
        }
    }
    /**
     * Releases Falcon resources.
     */
    public synchronized void close() {
        if (falcon != null) {
            falcon.delete();
        }
        instance = null;
    }
}
