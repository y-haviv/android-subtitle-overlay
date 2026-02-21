package com.example.subtitles.model.transcription.correction.whisper.lib;

import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.InputStream;

/**
 * ================================================================
 * WhisperLib (JNI Bridge)
 * ================================================================
 *
 * Java interface for the native Whisper C/C++ library.
 *
 * Responsibilities:
 *  - Load native shared library (libwhisper.so)
 *  - Expose JNI bindings for model loading, inference,
 *    segmentation and benchmarking.
 *
 * All heavy computation happens in native code.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class WhisperLib {

  /** Logging tag */
  private static final String TAG = "LibWhisper";

  /**
   * Loads native library on class initialization.
   */
  static {
    Log.d(TAG, "Loading libwhisper.so");
    System.loadLibrary("whisper");
  }

  // ================================================================
  // Context initialization
  // ================================================================

  /**
   * Initialize Whisper context from InputStream.
   *
   * @param inputStream model binary stream
   * @return native pointer to whisper_context
   */
  public static native long initContextFromInputStream(InputStream inputStream);

  /**
   * Initialize Whisper context from Android assets.
   *
   * @param assetManager asset manager
   * @param assetPath    model file inside assets
   * @return native pointer to whisper_context
   */
  public static native long initContextFromAsset(
          AssetManager assetManager,
          String assetPath
  );

  /**
   * Initialize Whisper context from absolute file path.
   *
   * @param modelPath path to model on disk
   * @return native pointer to whisper_context
   */
  public static native long initContext(String modelPath);

  /**
   * Frees native whisper_context.
   *
   * @param contextPtr native pointer
   */
  public static native void freeContext(long contextPtr);

  // ================================================================
  // Transcription
  // ================================================================

  /**
   * Runs full transcription on given audio buffer.
   *
   * @param contextPtr native context
   * @param numThreads number of CPU threads
   * @param audioData  PCM float audio buffer
   */
  public static native void fullTranscribe(
          long contextPtr,
          int numThreads,
          float[] audioData
  );

  // ================================================================
  // Segments retrieval
  // ================================================================

  /**
   * @return number of text segments produced
   */
  public static native int getTextSegmentCount(long contextPtr);

  /**
   * Returns text of a segment.
   */
  public static native String getTextSegment(long contextPtr, int index);

  /**
   * @return start time (t0) of segment in Whisper units
   */
  public static native long getTextSegmentT0(long contextPtr, int index);

  /**
   * @return end time (t1) of segment in Whisper units
   */
  public static native long getTextSegmentT1(long contextPtr, int index);

  // ================================================================
  // Metadata
  // ================================================================

  /**
   * @return detected spoken language
   */
  public static native String getDetectedLanguage(long contextPtr);

  /**
   * @return system / build information
   */
  public static native String getSystemInfo();

  // ================================================================
  // Benchmarks (debug / profiling)
  // ================================================================

  /**
   * Memory copy benchmark.
   */
  public static native String benchMemcpy(int nthread);

  /**
   * Matrix multiplication benchmark.
   */
  public static native String benchGgmlMulMat(int nthread);
}
