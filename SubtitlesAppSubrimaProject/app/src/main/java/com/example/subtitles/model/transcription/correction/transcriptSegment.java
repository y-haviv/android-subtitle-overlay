package com.example.subtitles.model.transcription.correction;

import android.util.Log;

import com.example.subtitles.model.transcription.correction.whisper.WhisperTranscriber;

import java.util.Locale;
/**
 * Represents a single transcription segment produced by a speech-to-text engine.
 *
 * A segment contains:
 *  - Start timestamp (milliseconds)
 *  - End timestamp (milliseconds)
 *  - Recognized sentence text
 *
 * This class acts as a lightweight data container with small utilities
 * for timestamp correction and text normalization.
 */
public class transcriptSegment {

    private static final String TAG = "transcriptSegment";

    /** Segment start time in milliseconds */
    private long start;

    /** Segment end time in milliseconds */
    private long end;

    /** Recognized sentence text */
    private String sentence;
    /**
     * Creates a new transcription segment.
     *
     * @param start    start timestamp in milliseconds
     * @param end      end timestamp in milliseconds
     * @param sentence recognized text
     */
    public transcriptSegment(long start, long end, String sentence) {
        this.start = start;
        this.end = end;
        this.sentence = sentence;
    }
    /**
     * @return segment start time in milliseconds
     */
    public long getStart() {
        return start;
    }
    /**
     * @return segment end time in milliseconds
     */
    public long getEnd() {
        return end;
    }
    /**
     * Adjusts timestamps produced by Vosk-style chunked transcription.
     *
     * Whisper/Vosk may return timestamps relative to a sliding audio chunk.
     * This method shifts the segment back by CHUNK_SEC in order to
     * align it with the global timeline.
     *
     * If adjustment fails, a warning is logged.
     */
    public void voskAjustTime() {
        // Check if timestamps exceed the chunk window
        if(start >= WhisperTranscriber.CHUNK_SEC*1000L || end >= WhisperTranscriber.CHUNK_SEC) {
            // Shift timestamps backward by chunk size
            start = Math.max(start-WhisperTranscriber.CHUNK_SEC*1000L, 0);
            end = Math.max(end-WhisperTranscriber.CHUNK_SEC*1000L, 0);
            // Valid segment after adjustment
            if(end!=start) {
                return;
            }
        }
        // Something went wrong
        Log.i(TAG, "*************** PROBLEM: in voskAjustTime ************");
        Log.i(TAG, "*************** text: " + this.toString() + " ************");
    }
    /**
     * @return recognized sentence
     */
    public String getSentence() {
        return sentence;
    }
    /**
     * Sets segment start time.
     */
    public void setStart(long start) {
        this.start = start;
    }

    /**
     * Sets segment end time.
     */
    public void setEnd(long end) {
        this.end = end;
    }

    /**
     * Sets recognized sentence.
     */
    public void setSentence(String sentence) {
        this.sentence = sentence;
    }

    /**
     * Returns a readable representation of the segment.
     *
     * Example:
     * [1200 --> 2400]:hello world
     */
    @Override
    public String toString() {
        return "[" + start + " --> " + end + "]:" + sentence;
    }
    /**
     * Normalizes sentence text:
     *  - Converts to lowercase
     *  - Removes punctuation
     *  - Collapses multiple spaces
     *
     * Useful for comparisons, indexing, or post-processing.
     */
    public void normalize() {
        if (sentence == null || sentence.isEmpty()) return;

        // Convert to lowercase using locale-safe method
        String result = sentence.toLowerCase(Locale.ROOT);

        // Remove punctuation characters
        result = result.replaceAll("[\\p{Punct}]", "");
        // Normalize whitespace
        result = result.replaceAll("\\s+", " ").trim();

        sentence = result;
    }
}
