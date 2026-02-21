package com.example.subtitles.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // Network safety timeouts
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.connect();

        // Validate HTTP response
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP error code: " + conn.getResponseCode());
        }

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(destFile)) {

            byte[] buf = new byte[4096];
            int len;

            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }

            out.flush();
            Log.i(TAG, "Downloaded file to: " + destFile.getAbsolutePath());

        } finally {
            conn.disconnect();
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
                     new ZipInputStream(new java.io.FileInputStream(zipFile))) {

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
