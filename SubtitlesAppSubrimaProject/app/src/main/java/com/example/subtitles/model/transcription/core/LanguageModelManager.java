package com.example.subtitles.model.transcription.core;

import android.content.Context;
import android.util.Log;

import com.example.subtitles.util.AssetUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


/**
 * Central manager responsible for loading, downloading, caching,
 * and validating Vosk speech recognition models by language code.
 *
 * Implements a singleton pattern and maintains a local disk cache
 * to avoid repeated downloads.
 */
public class LanguageModelManager {
    private static final String TAG = "LanguageModelManager";
    /** Singleton instance */
    private static volatile LanguageModelManager instance;

    /** Application context */
    private final Context context;

    /** Root directory where models are stored */
    private final File baseDir;

    /** In-memory cache mapping language code → model directory */
    private final Map<String, File> cache = Collections.synchronizedMap(new HashMap<>());

    /** Last successfully loaded model (used as fallback) */
    private volatile File lastSuccessful;

    /** Single-thread executor for model download/extraction */
    private ExecutorService modelExecutor = Executors.newSingleThreadExecutor();

    /** Folder name under filesDir */
    public static final String MODEL_DIR_NAME = "vosk_models";

    /** Set of all language codes with known models */
    private final Set<String> supportedLanguages;

    /**
     * Private constructor for singleton.
     * Initializes model directory, seeds default English model,
     * and builds supported language set.
     */
    private LanguageModelManager(Context ctx) throws IOException {
        context = ctx.getApplicationContext();
        baseDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
        // Create base directory if needed
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            Log.e(TAG, "Unable to create models directory: " + baseDir.getAbsolutePath());
        }
        // ─── Seed the built-in English model ───
        try {

            File enDir = new File(baseDir, "en");
            // Copy bundled English model from assets if missing
            if (!enDir.exists() || enDir.listFiles().length == 0) {
                AssetUtils.copyAssetFolder(context, MODEL_DIR_NAME + "/en", enDir);
                Log.i(TAG, "Default English model unzipped to: " + enDir.getAbsolutePath());
            }

            // Register English as cached fallback
            if (enDir.exists() && enDir.isDirectory() && enDir.listFiles().length > 0) {
                cache.put("en", enDir);
                lastSuccessful = enDir;
                Log.i(TAG, "Default English model loaded into cache");
            } else {
                Log.w(TAG, "Default English model directory was empty or missing!");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize default English model", e);
        }

        // Build supported language set from JSON
        ModelAsset asset = ModelAsset.getInstance(context);
        supportedLanguages = asset.getAllSupportedLanguageCodes();
        Log.i(TAG, "Supported languages: " + supportedLanguages);
    }

    /**
     * Returns singleton instance.
     */
    public static LanguageModelManager getInstance(Context ctx) throws IOException {
        if (instance == null) {
            synchronized (LanguageModelManager.class) {
                if (instance == null) {
                    instance = new LanguageModelManager(ctx);
                }
            }
        }
        return instance;
    }

    /**
     * Returns all language codes that have a model.
     */
    public Set<String> getSupportedLanguages() {
        // return an unmodifiable view so callers can’t tweak it:
        return Collections.unmodifiableSet(supportedLanguages);
    }

    /**
     * Checks whether a language is supported.
     */
    public boolean isLanguageSupported(String langCode) {
        return supportedLanguages.contains(langCode);
    }

    /**
     * If extracted model contains a single nested folder,
     * return the inner folder (flatten directory structure).
     */
    private File flattenIfNeeded(File dir) {
        File[] children = dir.listFiles();
        if (children != null && children.length == 1 && children[0].isDirectory()) {
            File single = children[0];
            Log.i(TAG, "flattenIfNeeded: promoting " + single.getName());
            return single;
        }
        return dir;
    }

    /**
     * Validates that directory exists and is non-empty.
     */
    private boolean isValidModelDir(File dir) {
        return dir != null && dir.exists() && dir.isDirectory() && dir.listFiles() != null && dir.listFiles().length > 0;
    }


    /**
     * Loads or downloads model for the requested language.
     *
     * Guarantees returning a valid directory or falls back
     * to the last successfully loaded model.
     */
    public synchronized File loadModel(String langCode) {
        Log.i(TAG, "[loadModel] requested langCode=" + langCode +
                ", cacheKeys=" + cache.keySet());

        // 1) Return from cache if available
        File cached = cache.get(langCode);
        if (cached != null && cached.isDirectory()) {
            Log.i(TAG, "loadModel: returning cached model for " + langCode);
            return cached;
        }
        // 2) Check if already using same model folder
        try {
            ModelAsset asset = ModelAsset.getInstance(context);
            if (lastSuccessful != null && asset.sameModel(langCode)) {
                return lastSuccessful;
            }
        } catch (Exception e) {
            Log.i(TAG, "loadModel: problem checking the last model folder if it the same to the new lang");
        }

        synchronized (cache) {
            // Double-check cache inside lock
            cached = cache.get(langCode);
            if (cached != null && cached.isDirectory()) {
                return cached;
            }

            // Submit download/extract task
            Future<File> future = modelExecutor.submit(() -> {

                Log.i(TAG, "[loadModel→executor] launching copyOrDownload for " + langCode);
                File newModel = ModelAsset.getInstance(context)
                        .copyOrDownload(context, langCode);
                if (newModel == null || !newModel.isDirectory()) {
                    Log.w(TAG, "[loadModel→executor] copyOrDownload returned NULL for " + langCode);
                    throw new IOException("ModelAsset returned null or invalid dir for " + langCode);
                }
                Log.i(TAG, "[loadModel→executor] got newModel at " + newModel.getAbsolutePath());
                newModel.setLastModified(System.currentTimeMillis());
                return newModel;
            });

            File resultDir = null;
            try {
                // Wait up to 40 seconds
                resultDir = future.get(40, TimeUnit.SECONDS);
                Log.i(TAG, "[loadModel] future.get() → resultDir.exists="
                        + (resultDir != null && resultDir.exists())
                        + ", isDir=" + (resultDir != null && resultDir.isDirectory())
                        + ", list=" + Arrays.toString(resultDir != null ? resultDir.list() : null));

                if (!isValidModelDir(resultDir)) {
                    throw new IOException("Model directory invalid for " + langCode);
                }
                resultDir = flattenIfNeeded(resultDir);

                // Cache result
                cache.put(langCode, resultDir);
                lastSuccessful = resultDir;
                Log.i(TAG, "loadModel: loaded new model for " + langCode);

            } catch (TimeoutException te) {
                Log.e(TAG, "loadModel: timeout fetching model for " + langCode, te);
                future.cancel(true);

            } catch (ExecutionException ee) {
                Log.e(TAG, "loadModel: execution error for " + langCode, ee.getCause());

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "loadModel: interrupted while fetching model for " + langCode, ie);

            } catch (IOException ioe) {
                Log.e(TAG, "loadModel: IO error for " + langCode, ioe);

            } finally {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }


            // Fallback
            if (!isValidModelDir(resultDir)) {
                Log.w(TAG, "loadModel: falling back to last successful model");
                resultDir = lastSuccessful;
            }

            if (resultDir != null) {
                Log.i(TAG, "[loadModel] returning modelDir=" + resultDir.getAbsolutePath() +
                        ", exists=" + resultDir.exists() +
                        ", files=" + Arrays.toString(resultDir.list()));
            } else {
                Log.e(TAG, "[loadModel] resultDir is null! returning null or crashing app");
            }

            return resultDir;
        }
    }


    // ==============================================================
    // ======================= ModelAsset ===========================
    // ==============================================================

    /**
     * Handles reading lang.json and downloading/unzipping models.
     */
    private static class ModelAsset {
        private static volatile ModelAsset instance;
        /** Language → ModelInfo map loaded from JSON */
        private final Map<String, ModelInfo> map;
        /** Base directory for extracted models */
        private final File baseDir;

        private final String DIR_NAME = "models";
        /** Folder name of last successful model */
        private String lastSuccessfulName = "";


        public static ModelAsset getInstance(Context ctx) throws IOException {
            if (instance == null) {
                synchronized (ModelAsset.class) {
                    if (instance == null) {
                        instance = new ModelAsset(ctx);
                    }
                }
            }
            return instance;
        }

        /**
         * Returns all languages that have a valid model URL.
         */
        public Set<String> getAllSupportedLanguageCodes() {
            Set<String> supported = new HashSet<>();
            for (String code : map.keySet()) {
                String url = getUrl(code);
                if (url != null && !url.isEmpty()) {
                    supported.add(code);
                }
            }
            return supported;
        }
        /**
         * Loads lang.json into memory.
         */
        private ModelAsset(Context ctx) throws IOException {
            Type type = new TypeToken<Map<String, ModelInfo>>() {
            }.getType();
            try (InputStreamReader reader = new InputStreamReader(
                    ctx.getAssets().open("lang.json"), "UTF-8")) {
                map = new Gson().fromJson(reader, type);
            } catch (IOException e) {
                Log.e(TAG, "Unable to load lang.json", e);
                throw e;
            }

            baseDir = new File(ctx.getFilesDir(), DIR_NAME);
            if (!baseDir.exists()) baseDir.mkdirs();
        }
        /**
         * Returns download URL or asset path.
         */
        private String getUrl(String langCode) {
            ModelInfo info = map.get(langCode);
            if (info == null) {
                Log.w(TAG, "No JSON entry for language: " + langCode);
                return null;
            }
            if (info.number == -1) {
                Log.i(TAG, "Language " + langCode + " is explicitly unsupported (code -1)");
                return null;
            }
            if (info.transcript_link == null || info.transcript_link.isEmpty()) {
                Log.i(TAG, "No model available for language: " + langCode);
                return null;
            }
            return info.transcript_link;
        }

        /**
         * Downloads or copies model ZIP and unpacks it.
         */
        public synchronized File copyOrDownload(Context context, String langCode) throws IOException {

            Log.i(TAG, "[copyOrDownloadInternal] langCode=" + langCode + ", JSON keys=" + map.keySet());


            ModelInfo info = map.get(langCode);
            if (info == null || info.transcript_folder == null || info.transcript_folder.isEmpty()) {
                Log.w(TAG, "Missing transcript_folder for lang: " + langCode);
                return null; 
            }
            Log.i(TAG, "[copyOrDownloadInternal] info.transcript_folder=" + info.transcript_folder);
            String folderName = info.transcript_folder;
            File outDir = new File(baseDir, folderName);

            // Already exists
            if (outDir.exists() && outDir.isDirectory() && outDir.listFiles().length > 0) {
                Log.i(TAG, "Model dir exists: " + outDir.getAbsolutePath());
                return outDir;
            }

            outDir.mkdirs();

            String modelUrl = getUrl(langCode);
            Log.i(TAG, "[copyOrDownloadInternal] modelUrl=" + modelUrl);
            if (modelUrl == null || modelUrl.isEmpty()) {
                throw new IOException("No model URL defined for " + langCode);
            }

            File zipFile = new File(baseDir, "model.zip");
            try {
                // Download or copy ZIP
                if (modelUrl.startsWith("http")) {
                    Log.i(TAG, "Downloading from HTTP: " + modelUrl);
                    downloadFile(modelUrl, zipFile);
                } else {
                    Log.i(TAG, "Copying ZIP from assets: " + modelUrl);
                    // asset-based ZIP
                    try (InputStream in = context.getAssets().open(modelUrl);
                         FileOutputStream out = new FileOutputStream(zipFile)) {
                        byte[] buf = new byte[4096];
                        int read;
                        while ((read = in.read(buf)) != -1) {
                            out.write(buf, 0, read);
                        }
                    }
                }

                // Unzip
                unzip(zipFile, outDir);
                Log.i(TAG, "[copyOrDownloadInternal] after unzip, outDir=" + outDir.getAbsolutePath() +
                        ", contents=" + Arrays.toString(outDir.list()));
                File[] ch = outDir.listFiles();
                if (ch != null && ch.length == 1 && ch[0].isDirectory()) {
                    File single = ch[0];
                    Log.i(TAG, "loadModel: flattening single-child dir " + single.getName());
                    outDir = single;
                }
            } finally {
                if (zipFile.exists()) zipFile.delete();
            }

            if (outDir.listFiles() == null || outDir.listFiles().length == 0) {
                throw new IOException("Model folder is empty after unzip for lang: " + langCode);
            }

            File[] dirs = baseDir.listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir.isDirectory()) {
                        String name = dir.getName();
                        if (!name.equals("en") && !name.equals(folderName)) {
                            deleteRecursively(dir);
                        }
                    }
                }
            }

            lastSuccessfulName = map.get(langCode).transcript_folder;
            return outDir;
        }
        /**
         * Checks if requested language uses same model folder
         * as the currently active one.
         */
        public boolean sameModel(String langCode) {
            if(lastSuccessfulName == null || lastSuccessfulName.isEmpty()) {
                return false;
            }
            try {
                String desiredFolder = map.get(langCode).transcript_folder;
                if(desiredFolder == null) {
                    throw new RuntimeException("lang folder for: "+ langCode+" not exist in json");
                }
                File dir = new File(baseDir, desiredFolder);
                return lastSuccessfulName.equals(desiredFolder) && dir.exists() && dir.isDirectory();
            } catch (Exception e) {
                Log.i(TAG, "problem to check transcript_folder");
                return false;
            }
        }

        // ---------- Utility Methods ----------
        private void unzip(File zipFile, File targetDir) throws IOException {
            if (!targetDir.exists()) targetDir.mkdirs();
            try (ZipInputStream zis = new ZipInputStream(
                    new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(targetDir, entry.getName());
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[4096];
                            int len;
                            while ((len = zis.read(buf)) != -1) out.write(buf, 0, len);
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

        /**
         * Deletes a directory and all its contents.
         */
        private void deleteRecursively(File fileOrDir) {
            if (fileOrDir.isDirectory()) {
                File[] children = fileOrDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            if (!fileOrDir.delete()) {
                Log.w(TAG, "Failed to delete " + fileOrDir.getAbsolutePath());
            }
        }

        private void downloadFile(String urlStr, File destFile) throws IOException {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error code: " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                Log.i(TAG, "Downloaded file to: " + destFile.getAbsolutePath());
            } finally {
                conn.disconnect();
            }
        }

        }

}
