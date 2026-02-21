package com.example.subtitles.model.transcription.core;

/**
 * Simple POJO describing a language model entry as defined in lang.json.
 * Each instance represents a single language and its associated model metadata.
 */
public class ModelInfo {

    /** Language code (e.g., "en", "es", "fr") */
    public String code;

    /** Human-readable language name (e.g., "English") */
    public String name;

    /**
     * Original numeric language identifier.
     * A value of -1 indicates the language is explicitly unsupported.
     */
    public int number;

    /**
     * URL or asset path to the transcription model zip file.
     * Empty string means no downloadable model exists.
     */
    public String transcript_link;

    /**
     * Folder name under app storage where the model will be extracted.
     */
    public String transcript_folder;
}
