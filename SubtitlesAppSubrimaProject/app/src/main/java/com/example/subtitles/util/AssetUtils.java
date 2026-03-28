package com.example.subtitles.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * -----------------------------------------------------------------------------
 * AssetUtils
 * -----------------------------------------------------------------------------
 *
 * Central utility class responsible for:
 *
 * • Copying files and folders from Android assets into internal storage
 * • Downloading model files from remote URLs
 * • Unzipping model archives
 * • Loading JSON-based configuration data
 *
 * This class is intentionally stateless and exposes only static methods.
 *
 * All operations are designed to be safe, idempotent, and reusable.
 */
public class AssetUtils {

    /** Logcat tag used by this class */
    private static final String TAG = "AssetUtils";

    /**
     * -------------------------------------------------------------------------
     * Runtime model filenames inside app-private storage (context.getFilesDir())
     * -------------------------------------------------------------------------
     *
     * These filenames are treated as canonical locations for model loading.
     * All model consumers should resolve paths through this utility to avoid
     * hard-coded duplicates across the project.
     */
    public static final String SILERO_MODEL_FILE = "lang_classifier_95.onnx";
    public static final String PYANNOTE_MODEL_FILE = "model.onnx";
    public static final String WHISPER_TINY_MODEL_FILE = "ggml-tiny.bin";
    public static final String VOSK_MODEL_DIR = "vosk_models";
    public static final String DEFAULT_VOSK_LANGUAGE = "en";

    private static final String SILERO_MODEL_URL =
            "https://huggingface.co/Derur/silero-models/resolve/main/lang95/lang_classifier_95.onnx";
    private static final String PYANNOTE_MODEL_URL =
            "https://huggingface.co/deepghs/pyannote-embedding-onnx/resolve/main/model.onnx";
    private static final String WHISPER_TINY_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin";
    private static final String DEFAULT_VOSK_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

    /**
     * Single worker used to download required runtime models sequentially.
     *
     * Sequential behavior is intentional to:
     * 1. Keep progress reporting deterministic for the UI.
     * 2. Reduce peak bandwidth / memory pressure on low-end devices.
     * 3. Minimize simultaneous file-system writes.
     */
    private static final ExecutorService MODEL_DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * Callback for runtime model availability workflow.
     *
     * Threading contract:
     * - All callback methods are dispatched on the Android main thread.
     * - Download and file I/O operations execute on a background executor.
     */
    public interface ModelDownloadCallback {
        /**
         * Reports progress for the currently processed model.
         *
         * @param modelName  Human-readable model label for UI display
         * @param percentage Progress in [0..100]
         */
        void onProgress(String modelName, int percentage);

        /**
         * Called once all required models are present and valid.
         */
        void onSuccess();

        /**
         * Called when any download/storage step fails.
         *
         * @param errorMessage User-friendly error message
         */
        void onError(String errorMessage);
    }

    /** Internal progress callback used by stream downloader. */
    private interface ProgressListener {
        void onProgress(int percentage);
    }

    /** Value object describing one required runtime model. */
    private static class RuntimeModel {
        final String displayName;
        final String fileName;
        final String url;

        RuntimeModel(String displayName, String fileName, String url) {
            this.displayName = displayName;
            this.fileName = fileName;
            this.url = url;
        }
    }

    private static final RuntimeModel[] REQUIRED_MODELS = new RuntimeModel[]{
            new RuntimeModel("Silero Language Model", SILERO_MODEL_FILE, SILERO_MODEL_URL),
            new RuntimeModel("Pyannote Speaker Diarization", PYANNOTE_MODEL_FILE, PYANNOTE_MODEL_URL),
            new RuntimeModel("Whisper Tiny", WHISPER_TINY_MODEL_FILE, WHISPER_TINY_MODEL_URL)
    };

    /**
     * Returns the absolute file handle for a runtime model in app-private storage.
     *
     * @param context  Application context
     * @param fileName Runtime model filename
     * @return File under context.getFilesDir()
     */
    public static File getRuntimeModelFile(Context context, String fileName) {
        return new File(context.getFilesDir(), fileName);
    }

    /**
     * Returns a runtime model file and verifies that it exists and is non-empty.
     *
     * @param context  Application context
     * @param fileName Runtime model filename
     * @return Existing model file
     * @throws IOException If file is missing or empty
     */
    public static File getRequiredRuntimeModelFile(Context context, String fileName) throws IOException {
        File modelFile = getRuntimeModelFile(context, fileName);
        if (!modelFile.exists() || modelFile.length() <= 0L) {
            throw new IOException("Model file missing: " + fileName + ". Please download runtime models first.");
        }
        return modelFile;
    }

    /** Returns the root Vosk model directory inside app-private storage. */
    public static File getVoskModelsDir(Context context) {
        return new File(context.getFilesDir(), VOSK_MODEL_DIR);
    }

    /** Returns the canonical location for the default English Vosk model. */
    public static File getDefaultVoskModelDir(Context context) {
        return new File(getVoskModelsDir(context), DEFAULT_VOSK_LANGUAGE);
    }

    /** Checks whether the default English Vosk model is already extracted and usable. */
    public static boolean hasDefaultVoskModel(Context context) {
        return isValidVoskModelDir(getDefaultVoskModelDir(context));
    }

    /** Ensures filesDir/vosk_models/en contains the extracted small English Vosk model. */
    public static File ensureDefaultVoskModel(Context context) throws IOException {
        return ensureDefaultVoskModel(context, null);
    }

    /** Ensures filesDir/vosk_models/en contains the extracted small English Vosk model. */
    public static File ensureDefaultVoskModel(Context context, ProgressListener progressListener) throws IOException {
        Context appContext = context.getApplicationContext();
        File voskDir = getVoskModelsDir(appContext);
        if (!voskDir.exists() && !voskDir.mkdirs()) {
            throw new IOException("Unable to create Vosk models directory: " + voskDir.getAbsolutePath());
        }

        File langDir = getDefaultVoskModelDir(appContext);
        if (isValidVoskModelDir(langDir)) {
            return langDir;
        }

        File stagingDir = new File(voskDir, DEFAULT_VOSK_LANGUAGE + "_download");
        File zipFile = new File(voskDir, DEFAULT_VOSK_LANGUAGE + ".zip");

        deleteRecursively(stagingDir);
        deleteRecursively(langDir);

        if (!stagingDir.mkdirs()) {
            throw new IOException("Unable to create staging directory: " + stagingDir.getAbsolutePath());
        }

        try {
            downloadFile(DEFAULT_VOSK_MODEL_URL, zipFile, progressListener);
            unzip(zipFile, stagingDir);

            File preparedDir = locateVoskModelRoot(stagingDir);
            if (preparedDir == null) {
                throw new IOException("Downloaded Vosk archive does not contain a valid Vosk model layout. Contents: "
                        + describeDirectoryContents(stagingDir));
            }

            if (!langDir.mkdirs()) {
                throw new IOException("Unable to create target Vosk model directory: " + langDir.getAbsolutePath());
            }

            copyDirectoryContents(preparedDir, langDir);

            if (!isValidVoskModelDir(langDir)) {
                throw new IOException("Default Vosk English model is incomplete: " + langDir.getAbsolutePath());
            }
        } finally {
            if (zipFile.exists() && !zipFile.delete()) {
                Log.w(TAG, "Failed to delete temporary Vosk ZIP: " + zipFile.getAbsolutePath());
            }
            deleteRecursively(stagingDir);
        }

        if (!isValidVoskModelDir(langDir)) {
            throw new IOException("Default Vosk English model is incomplete: " + langDir.getAbsolutePath());
        }

        return langDir;
    }

    /**
     * -------------------------------------------------------------------------
    * Ensures the required AI models are available in internal storage.
     * -------------------------------------------------------------------------
     *
     * Behavior summary:
     * - Executes on a dedicated background executor.
     * - Skips already existing non-empty files (idempotent).
     * - Downloads missing models in a fixed sequence.
     * - Reports UI progress and completion/error on main thread.
     *
     * @param context  Any context (application context is derived internally)
     * @param callback UI callback invoked on main thread
     */
    public static void ensureRuntimeModelsDownloaded(Context context, ModelDownloadCallback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        MODEL_DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                File filesDir = appContext.getFilesDir();
                if (!filesDir.exists() && !filesDir.mkdirs()) {
                    throw new IOException("Unable to create internal storage directory.");
                }

                for (RuntimeModel model : REQUIRED_MODELS) {
                    File modelFile = new File(filesDir, model.fileName);
                    if (modelFile.exists() && modelFile.length() > 0L) {
                        postProgress(mainHandler, callback, model.displayName, 100);
                        continue;
                    }

                    postProgress(mainHandler, callback, model.displayName, 0);
                    try {
                        downloadFile(model.url, modelFile, percent ->
                                postProgress(mainHandler, callback, model.displayName, percent));
                    } catch (Exception e) {
                        throw new IOException("Failed while downloading " + model.displayName + ": " + e.getMessage(), e);
                    }
                }

                final String voskDisplayName = "Vosk English Model";
                if (hasDefaultVoskModel(appContext)) {
                    postProgress(mainHandler, callback, voskDisplayName, 100);
                } else {
                    postProgress(mainHandler, callback, voskDisplayName, 0);
                    ensureDefaultVoskModel(appContext, percent ->
                            postProgress(mainHandler, callback, voskDisplayName, percent));
                    postProgress(mainHandler, callback, voskDisplayName, 100);
                }

                mainHandler.post(callback::onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "ensureRuntimeModelsDownloaded failed", e);
                final String message = buildNetworkFriendlyError(e);
                mainHandler.post(() -> callback.onError(message));
            }
        });
    }

    /** Dispatches a normalized progress value on the main thread. */
    private static void postProgress(Handler handler,
                                     ModelDownloadCallback callback,
                                     String modelName,
                                     int percentage) {
        int safePercent = Math.max(0, Math.min(100, percentage));
        handler.post(() -> callback.onProgress(modelName, safePercent));
    }

    /**
     * Converts low-level exceptions into stable, user-friendly messages.
     */
    private static String buildNetworkFriendlyError(Exception e) {
        if (e instanceof UnknownHostException) {
            return "No internet connection. Please connect to the internet and try again.";
        }
        if (e instanceof SocketTimeoutException) {
            return "Download timed out. Please check your connection and try again.";
        }
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return "Failed to download required AI models.";
        }
        return "Failed to download required AI models: " + msg;
    }

    /**
     * -------------------------------------------------------------------------
     * Copies a single asset file into app internal storage
     * -------------------------------------------------------------------------
     *
     * If the file already exists, it will NOT be overwritten.
     *
     * @param context   Application context (used to access AssetManager)
     * @param assetName Name of the asset file
     * @return File pointing to the copied (or existing) file
     */
    public static File copyFileIfNotExists(Context context, String assetName) throws IOException {

        // App's internal files directory
        File targetDir = context.getFilesDir();

        // Destination file
        File outFile = new File(targetDir, assetName);

        // If file already exists -> reuse
        if (outFile.exists()) {
            Log.i(TAG, "Asset already exists: " + outFile.getAbsolutePath());
            return outFile;
        }

        AssetManager am = context.getAssets();

        // Open asset input stream and copy into output file
        try (InputStream in = am.open(assetName);
             FileOutputStream out = new FileOutputStream(outFile)) {

            byte[] buf = new byte[4096];
            int len;

            // Stream copy loop
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }

            out.flush();
            Log.i(TAG, "Copied asset to: " + outFile.getAbsolutePath());
            return outFile;

        } catch (IOException e) {
            Log.e(TAG, "copyFileIfNotExists failed for " + assetName, e);
            throw e;
        }
    }

    /**
     * -------------------------------------------------------------------------
     * Recursively copies an entire asset folder to disk
     * -------------------------------------------------------------------------
     *
     * Works for:
     * • Single files
     * • Nested directory trees
     *
     * @param ctx         Context for AssetManager
     * @param assetFolder Path inside assets
     * @param destDir     Target file or directory
     */
    public static void copyAssetFolder(Context ctx, String assetFolder, File destDir) throws IOException {

        AssetManager am = ctx.getAssets();

        // List entries inside asset path
        String[] entries = am.list(assetFolder);

        // If no entries -> treat as single file
        if (entries == null || entries.length == 0) {

            try (InputStream in = am.open(assetFolder);
                 FileOutputStream out = new FileOutputStream(destDir)) {

                byte[] buf = new byte[4096];
                int len;

                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }

        } else {

            // Create directory
            destDir.mkdirs();

            // Recursively copy children
            for (String e : entries) {
                String childAssetPath = assetFolder + "/" + e;
                File childDest = new File(destDir, e);
                copyAssetFolder(ctx, childAssetPath, childDest);
            }
        }
    }

    /**
     * -------------------------------------------------------------------------
     * Loads a JSON file from assets and converts it to JSONObject
     * -------------------------------------------------------------------------
     *
     * @param ctx       Context
     * @param assetName JSON file name
     * @return Parsed JSONObject
     */
    public static JSONObject loadJsonObject(Context ctx, String assetName)
            throws IOException, JSONException {

        try (InputStream is = ctx.getAssets().open(assetName)) {

            int size = is.available();
            byte[] buffer = new byte[size];

            // Read entire file
            is.read(buffer);

            String json = new String(buffer, StandardCharsets.UTF_8);
            return new JSONObject(json);
        }
    }

    /**
     * -------------------------------------------------------------------------
     * Downloads a remote file into destination file
     * -------------------------------------------------------------------------
     *
     * @param urlStr   HTTP/HTTPS URL
     * @param destFile Destination file
     */
    public static void downloadFile(String urlStr, File destFile) throws IOException {

        downloadFile(urlStr, destFile, null);
    }

    /**
     * Internal streaming downloader with optional percentage callback.
     *
     * Implementation notes:
     * - Writes into a temporary .part file first.
     * - Replaces destination only after full successful transfer.
     * - Cleans up temporary artifacts on failure.
     */
    private static void downloadFile(String urlStr, File destFile, ProgressListener progressListener) throws IOException {

        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent.getAbsolutePath());
        }

        File tempFile = new File(destFile.getAbsolutePath() + ".part");

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // Network safety timeouts
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "SubtitlesApp/1.0");
        conn.connect();

        // Validate HTTP response
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP error code: " + conn.getResponseCode());
        }

        int contentLength = conn.getContentLength();

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(tempFile)) {

            byte[] buf = new byte[4096];
            int len;
            long totalRead = 0L;
            int lastPercent = -1;

            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                totalRead += len;

                if (progressListener != null && contentLength > 0) {
                    int percent = (int) ((100L * totalRead) / contentLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progressListener.onProgress(percent);
                    }
                }
            }

            out.flush();

            if (destFile.exists() && !destFile.delete()) {
                throw new IOException("Failed to replace existing file: " + destFile.getAbsolutePath());
            }
            if (!tempFile.renameTo(destFile)) {
                throw new IOException("Failed to finalize download: " + destFile.getAbsolutePath());
            }

            if (progressListener != null) {
                progressListener.onProgress(100);
            }

            Log.i(TAG, "Downloaded file to: " + destFile.getAbsolutePath());

        } finally {
            conn.disconnect();
            if (tempFile.exists() && !tempFile.equals(destFile)) {
                // Clean up temp file after failures.
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    /**
     * -------------------------------------------------------------------------
     * Extracts ZIP archive into target directory
     * -------------------------------------------------------------------------
     *
     * @param zipFile   ZIP file
     * @param targetDir Output directory
     */
    public static void unzip(File zipFile, File targetDir) throws IOException {

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        try (ZipInputStream zis =
                 new ZipInputStream(new FileInputStream(zipFile))) {

            ZipEntry entry;

            // Iterate through ZIP entries
            while ((entry = zis.getNextEntry()) != null) {

                File outFile = new File(targetDir, entry.getName());

                if (entry.isDirectory()) {

                    // Create folder
                    outFile.mkdirs();

                } else {

                    // Ensure parent folder exists
                    outFile.getParentFile().mkdirs();

                    try (FileOutputStream out =
                                 new FileOutputStream(outFile)) {

                        byte[] buf = new byte[4096];
                        int len;

                        while ((len = zis.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }

                        out.flush();
                    }
                }

                zis.closeEntry();
            }

            Log.i(TAG, "Unzipped to: " + targetDir.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "unzip failed: " + zipFile.getAbsolutePath(), e);
            throw e;
        }
    }

    private static File locateVoskModelRoot(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        if (isValidVoskModelDir(dir)) {
            return dir;
        }

        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }

        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }

            File nested = locateVoskModelRoot(child);
            if (nested != null) {
                return nested;
            }
        }

        return null;
    }

    private static boolean isValidVoskModelDir(File dir) {
        return isValidVoskModelDirV2(dir) || isValidVoskModelDirV1(dir);
    }

    private static boolean isValidVoskModelDirV2(File dir) {
        return dir != null
                && dir.exists()
                && dir.isDirectory()
                && new File(dir, "am/final.mdl").isFile()
                && new File(dir, "conf/model.conf").isFile()
                && (new File(dir, "conf/mfcc.conf").isFile()
                || new File(dir, "conf/fbank.conf").isFile());
    }

    private static boolean isValidVoskModelDirV1(File dir) {
        return dir != null
                && dir.exists()
                && dir.isDirectory()
                && new File(dir, "final.mdl").isFile()
                && (new File(dir, "mfcc.conf").isFile()
                || new File(dir, "fbank.conf").isFile());
    }

    private static void copyDirectoryContents(File sourceDir, File targetDir) throws IOException {
        File[] children = sourceDir.listFiles();
        if (children == null) {
            throw new IOException("Cannot read directory: " + sourceDir.getAbsolutePath());
        }

        for (File child : children) {
            File target = new File(targetDir, child.getName());
            if (child.isDirectory()) {
                if (!target.exists() && !target.mkdirs()) {
                    throw new IOException("Unable to create directory: " + target.getAbsolutePath());
                }
                copyDirectoryContents(child, target);
            } else {
                copyFile(child, target);
            }
        }
    }

    private static void copyFile(File sourceFile, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream in = new FileInputStream(sourceFile);
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return;
        }

        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }

        if (!target.delete()) {
            Log.w(TAG, "Failed to delete path: " + target.getAbsolutePath());
        }
    }

    private static String describeDirectoryContents(File dir) {
        if (dir == null || !dir.exists()) {
            return "<missing>";
        }

        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            return "<empty>";
        }

        StringBuilder builder = new StringBuilder();
        for (File child : children) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(child.getName());
            if (child.isDirectory()) {
                builder.append('/');
            }
        }
        return builder.toString();
    }

    /**
     * -------------------------------------------------------------------------
     * Copies model from assets OR downloads from URL and unpacks it
     * -------------------------------------------------------------------------
     *
     * @param context        Context
     * @param modelSpecifier URL or asset name
     * @param outDir         Destination directory
     * @return Directory containing extracted model
     */
    public static File copyOrDownload(Context context,
                                      String modelSpecifier,
                                      File outDir) throws IOException {

        // If model already exists -> reuse
        if (outDir.exists()
                && outDir.isDirectory()
                && outDir.listFiles().length > 0) {

            Log.i(TAG, "Model dir exists: " + outDir.getAbsolutePath());
            return outDir;
        }

        // Ensure directory exists
        outDir.mkdirs();

        // Case 1: Remote URL
        if (modelSpecifier.startsWith("http")) {

            File zipFile = new File(outDir.getParentFile(), "model.zip");

            try {
                downloadFile(modelSpecifier, zipFile);
                unzip(zipFile, outDir);
            } finally {
                if (zipFile.exists()) zipFile.delete();
            }

        } else {

            // Case 2: Asset ZIP
            AssetManager am = context.getAssets();

            try (InputStream assetStream = am.open(modelSpecifier)) {

                File zipFile = new File(outDir.getParentFile(), "model.zip");

                try (FileOutputStream fos = new FileOutputStream(zipFile)) {

                    byte[] buf = new byte[4096];
                    int r;

                    while ((r = assetStream.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                }

                unzip(zipFile, outDir);
                zipFile.delete();

            } catch (IOException e) {
                throw new IOException(
                        "Asset model not found or unzip failed for "
                                + modelSpecifier, e);
            }
        }

        return outDir;
    }

    /**
     * -------------------------------------------------------------------------
     * Loads JSON map and returns ordered list of values
     * -------------------------------------------------------------------------
     *
     * JSON format:
     * {
     *   "0": "valueA",
     *   "1": "valueB"
     * }
     */
    public static List<String> loadStringList(Context ctx, String assetName)
            throws IOException {

        try (InputStream is = ctx.getAssets().open(assetName);
             InputStreamReader reader =
                     new InputStreamReader(is, "UTF-8")) {

            Type mapType =
                    new TypeToken<Map<String, String>>() {}.getType();

            Map<String, String> map =
                    new Gson().fromJson(reader, mapType);

            // Sort numerically by key to ensure stable order
            return map.entrySet()
                    .stream()
                    .sorted(Comparator.comparingInt(
                            e -> Integer.parseInt(e.getKey())))
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());
        }
    }

    /**
     * -------------------------------------------------------------------------
     * Loads language code → language name mapping
     * -------------------------------------------------------------------------
     *
     * Example JSON entry:
     * "30": "en, English"
     *
     * Result:
     * "en" -> "English"
     */
    public static Map<String, String> loadLangCodeMap(
            Context ctx,
            String filename) throws IOException {

        try (InputStream is = ctx.getAssets().open(filename)) {

            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);

            String json =
                    new String(buffer, StandardCharsets.UTF_8);

            JSONObject obj = new JSONObject(json);
            Map<String, String> langMap = new HashMap<>();

            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {

                String key = it.next();
                String val = obj.getString(key);

                String[] parts = val.split(",", 2);

                if (parts.length == 2) {
                    langMap.put(parts[0].trim(),
                            parts[1].trim());
                } else {
                    langMap.put(parts[0].trim(),
                            parts[0].trim());
                }
            }

            return langMap;

        } catch (JSONException e) {
            throw new IOException("Invalid JSON in " + filename, e);
        }
    }
}
