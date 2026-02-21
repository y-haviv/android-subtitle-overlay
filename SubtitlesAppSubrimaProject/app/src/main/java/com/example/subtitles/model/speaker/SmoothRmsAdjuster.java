package com.example.subtitles.model.speaker;

/**
 * Utility for smoothing RMS energy and adapting silence threshold.
 *
 * <p>
 * Uses Exponential Moving Average (EMA) and gently shifts
 * silence threshold toward observed RMS.
 */

public class SmoothRmsAdjuster {

    // Constants - matching the Python BasicConfig values
    public static final double RMS_SMOOTHING = 0.94;
    public static final double RMS_THRESHOLD_MIN = 0.0001;
    public static final double RMS_THRESHOLD_MAX = 0.0006;
    public static final double RMS_LR_THRESH = 0.00005;
    public static final double RMS_THRESHOLD_DEFAULT = 0.0002;
    public static final double EPSILON = 1e-7;

    /**
     * Smooths the RMS average using EMA and adjusts the silence threshold
     * gently based on the deviation from the new average.
     *
     * @param rmsAvg        Current smoothed RMS value
     * @param rmsNew        New raw RMS measurement
     * @param silenceThresh Current silence threshold
     * @return float[]{ newSmoothedRms, newSilenceThreshold }
     */
    public static double[] smoothAndAdjust(double rmsAvg, double rmsNew, double silenceThresh) {
        double newAvg = RMS_SMOOTHING * rmsAvg + (1 - RMS_SMOOTHING) * rmsNew;
        if (Math.abs(newAvg - silenceThresh) > EPSILON) {
            silenceThresh += Math.signum(newAvg - silenceThresh) * RMS_LR_THRESH;
        }
        silenceThresh = clamp(silenceThresh, RMS_THRESHOLD_MIN, RMS_THRESHOLD_MAX);
        return new double[]{newAvg, silenceThresh};
    }

    /**
     * Clamp a value between min and max.
     */
    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
