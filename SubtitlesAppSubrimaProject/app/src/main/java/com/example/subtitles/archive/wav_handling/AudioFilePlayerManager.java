package com.example.subtitles.archive.wav_handling;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;


import java.io.File;
import java.io.IOException;

/**
 * Manages playback of a single audio file (MP3/WAV) stored in your assets folder.
 */
public class AudioFilePlayerManager {
    private static final String TAG = "AudioFilePlayerMgr";

    private final Context context;
    private final String assetFilename;
    private MediaPlayer mediaPlayer;
    private File playbackFile;
    private boolean isPrepared = false;

    private Runnable onCompletionCallback;



    /**
     * @param context       Android context
     * @param assetFilename Filename in assets (e.g. "test.wav" or "track.mp3")
     * @throws IOException  if copying the asset fails
     */
    public AudioFilePlayerManager(Context context, String assetFilename) throws IOException {
        this.context = context.getApplicationContext();
        this.assetFilename = assetFilename;

        // Copy asset to a file we can play from
        playbackFile = com.example.subtitles.util.AssetUtils.copyFileIfNotExists(this.context, assetFilename);
        Log.i(TAG, "Asset copied to: " + playbackFile.getAbsolutePath());

        initPlayer();
    }

    private void initPlayer() {
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(playbackFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                isPrepared = true;
                Log.i(TAG, "MediaPlayer prepared, duration=" + mp.getDuration() + "ms");
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.i(TAG, "Playback completed");
                if (onCompletionCallback != null) {
                    onCompletionCallback.run();
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Playback error what=" + what + " extra=" + extra);
                return true; // handled
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "Failed to set data source or prepare MediaPlayer", e);
        }
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public void setOnCompletionCallback(Runnable callback) {
        this.onCompletionCallback = callback;
    }

    /** Starts playback if prepared; logs if not ready yet. */
    public void start() {
        if (mediaPlayer == null) {
            Log.w(TAG, "start() called but mediaPlayer is null");
            return;
        }
        if (!isPrepared) {
            Log.w(TAG, "start() called before MediaPlayer prepared");
            mediaPlayer.setOnPreparedListener(mp -> {
                isPrepared = true;
                mp.start();
                Log.i(TAG, "Playback started after prepare");
            });
        } else {
            if(mediaPlayer.isPlaying()) return;
            mediaPlayer.start();
            Log.i(TAG, "Playback started");
        }
    }

    /** Stops playback if playing, resets to start. */
    public void stop() {
        if (mediaPlayer == null) {
            Log.w(TAG, "stop() called but mediaPlayer is null");
            return;
        }
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            Log.i(TAG, "Playback stopped");
            // after stop(), need to prepare again for future start()
            try {
                mediaPlayer.reset();
                mediaPlayer.setDataSource(playbackFile.getAbsolutePath());
                mediaPlayer.prepare();
                isPrepared = true;
                Log.i(TAG, "MediaPlayer re-prepared after stop");
            } catch (IOException e) {
                Log.e(TAG, "Failed to re-prepare after stop", e);
            }
        } else {
            Log.i(TAG, "stop() called but nothing was playing");
        }
    }

    /**
     * Releases all resources. After this, the manager should not be used.
     */
    public void destroy() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
            isPrepared = false;
            Log.i(TAG, "MediaPlayer destroyed");
        }
    }
}
