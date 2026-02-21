package com.example.subtitles.archive.vosk_legacy;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;

/**
 * Cleans up unused Vosk model directories older than MAX_AGE_MS.
 * Runs under WorkManager; never throws, always returns a success or retry.
 *
 * PeriodicWorkRequest cleanupRequest = new PeriodicWorkRequest.Builder(
 *                 ModelCleanupWorker.class,
 *                 24, TimeUnit.HOURS       // run once a day
 *         )
 *                 .build();
 *
 *         WorkManager.getInstance(context)
 *                 .enqueueUniquePeriodicWork(
 *                         "model_cleanup",                       // a unique name for this work
 *                         ExistingPeriodicWorkPolicy.KEEP,       // don’t replace if already scheduled
 *                         cleanupRequest
 *                 );
 */
public class ModelCleanupWorker extends Worker {
    private static final String TAG = "ModelCleanupWorker";

    /** Directory name under getFilesDir() where models live */
    public static final String MODEL_DIR_NAME = "vosk_models";

    /** Age threshold: 48 hours in milliseconds */
    private static final long MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000;

    public ModelCleanupWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params
    ) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            File base = new File(getApplicationContext().getFilesDir(), MODEL_DIR_NAME);
            if (!base.exists() || !base.isDirectory()) {
                // Nothing to do
                return Result.success();
            }

            long now = System.currentTimeMillis();
            File[] children = base.listFiles();
            if (children == null) {
                return Result.success();
            }

            for (File modelDir : children) {
                try {
                    if (modelDir.isDirectory()) {
                        long age = now - modelDir.lastModified();
                        if (age > MAX_AGE_MS) {
                            deleteRecursive(modelDir);
                            Log.i(TAG, "Deleted old model: " + modelDir.getName());
                        }
                    }
                } catch (Exception inner) {
                    // Log but continue with the next directory
                    Log.w(TAG, "Failed to clean " + modelDir.getName(), inner);
                }
            }
            return Result.success();
        } catch (Exception e) {
            // If something truly unexpected happens, retry later
            Log.e(TAG, "Cleanup worker encountered fatal error", e);
            return Result.retry();
        }
    }

    /**
     * Recursively deletes a directory and its contents.
     * Silently ignores failures on delete() calls.
     */
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Could not delete: " + file.getAbsolutePath());
        }
    }
}
