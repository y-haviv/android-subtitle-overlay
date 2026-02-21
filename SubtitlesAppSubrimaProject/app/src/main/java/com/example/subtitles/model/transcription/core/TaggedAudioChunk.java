package com.example.subtitles.model.transcription.core;

/**
 * ================================================================
 * TaggedAudioChunk
 * ================================================================
 *
 * Represents a small audio chunk with:
 *  - raw PCM data (`short[]`)
 *  - optional flag indicating a Vosk reset before this chunk
 *  - associated ground-truth timestamp
 *
 * Provides helpers to get audio as float array for transcription models.
 */
public class TaggedAudioChunk {

    /** Raw PCM audio data (16-bit) */
    private final short[] audio;

    /** Flag indicating if Vosk reset occurred before this chunk */
    private boolean voskResetBefore;  // mutable flag

    /** True ground timestamp for this chunk (e.g., system or stream time) */
    private final long trueGroundTime;

    // ================================================================
    // Constructor
    // ================================================================

    /**
     * Create a new TaggedAudioChunk.
     *
     * @param audio short[] PCM audio samples
     * @param time  ground-truth timestamp
     */
    public TaggedAudioChunk(short[] audio, long time) {
        this.audio = audio;
        this.voskResetBefore = false;
        this.trueGroundTime = time;
    }

    // ================================================================
    // Flag manipulation
    // ================================================================

    /** Mark that a Vosk reset happened before this chunk */
    public void setResetBefore() {
        this.voskResetBefore = true;
    }

    /** Returns whether a Vosk reset happened before this chunk */
    public boolean getResetBefore() {
        return voskResetBefore;
    }

    // ================================================================
    // Audio access
    // ================================================================

    /** Return raw audio as short array */
    public short[] getShortAudio() {
        return audio;
    }

    /** Return audio as float array normalized to [-1.0, 1.0] */
    public float[] getFloatAudio() {
        return shortToFloat(audio);
    }

    // ================================================================
    // Timestamp
    // ================================================================

    /** Returns the ground-truth timestamp of this chunk */
    public long getTime() {
        return trueGroundTime;
    }

    // ================================================================
    // Utility
    // ================================================================

    /**
     * Convert short PCM [-32768..32767] to float [-1.0..1.0].
     *
     * @param input short array
     * @return normalized float array
     */
    private float[] shortToFloat(short[] input) {
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = input[i] / 32768f;
        }
        return output;
    }
}
