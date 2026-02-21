package com.example.subtitles.archive.translation;

import android.content.Context;
import android.util.Log;


import com.example.subtitles.archive.translation.tokenization.SentencePieceProcessor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ai.onnxruntime.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;


/**
 * Translator wraps SentencePiece and ONNX Runtime to perform on-device many-to-many translation.
 * Simplified real-time greedy decoding without cache.
 */
public class Translator implements AutoCloseable {
    private static final String TAG = "Translator";
    private static final String ASSET_DIR = "m2m100";
    private static final int MAX_OUTPUT_LENGTH = 256;

    private final Context ctx;
    private final OrtEnvironment env;
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private SentencePieceProcessor spm;
    private Map<String, Integer> langTokenToId;

    private int bosId, eosId, padId, unkId;
    private int srcLangTokenId, tgtLangTokenId;

    private final AtomicBoolean isTranslating = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public Translator(Context ctx, String sourceLang, String targetLang) throws TranslationException {
        try {
            this.ctx = ctx.getApplicationContext();
            this.env = OrtEnvironment.getEnvironment();
            loadSentencePieceModel();
            loadSpecialTokens();
            initIds();
            setSourceLanguage(sourceLang);
            setTargetLanguage(targetLang);
        } catch (IOException | JSONException | OrtException e) {
            throw new TranslationException("Translator initialization failed", e);
        }
    }

    private void loadSentencePieceModel() throws IOException {
        File modelFile = copyAsset(ASSET_DIR + "/source.spm");
        spm = new SentencePieceProcessor();
        try {
            spm.load(modelFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load SentencePiece model", e);
            throw new IOException("SPM load failed", e);
        }
    }

    private void loadSpecialTokens() throws IOException, JSONException {
        File jsonFile = copyAsset(ASSET_DIR + "/special_tokens_map.json");
        String json = readStreamToString(new java.io.FileInputStream(jsonFile));
        JSONObject obj = new JSONObject(json);
        JSONArray tokens = obj.getJSONArray("additional_special_tokens");
        langTokenToId = new HashMap<>();
        for (int i = 0; i < tokens.length(); i++) {
            String token = tokens.getString(i);
            String lang = token.replace("__", "");
            langTokenToId.put(lang, spm.pieceToId(token));
        }
        Log.d(TAG, "Supported languages: " + langTokenToId.keySet());
    }

    private void initIds() {
        bosId = spm.bosId();
        eosId = spm.eosId();
        padId = spm.padId();
        unkId = spm.unkId();
    }

    public synchronized void setSourceLanguage(String src) {
        if (isTranslating.get())
            throw new IllegalStateException("Cannot change language while translating");
        srcLangTokenId = langTokenToId.getOrDefault(src, unkId);
    }

    public synchronized void setTargetLanguage(String tgt) throws OrtException, IOException {
        if (isTranslating.get())
            throw new IllegalStateException("Cannot change language while translating");
        tgtLangTokenId = langTokenToId.getOrDefault(tgt, unkId);
        if (encoderSession == null || decoderSession == null) initSessions();
    }

    private void initSessions() throws OrtException, IOException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.addConfigEntry("session.set_denormal_as_zero", "1");
        encoderSession = env.createSession(copyAsset(ASSET_DIR + "/onnx/encoder_model_quantized.onnx").getAbsolutePath(), opts);
        decoderSession = env.createSession(copyAsset(ASSET_DIR + "/onnx/decoder_model_merged_quantized.onnx").getAbsolutePath(), opts);
        for (Map.Entry<String, NodeInfo> entry : encoderSession.getOutputInfo().entrySet()) {
            Log.d("ONNX-EncoderOutput", "Name: " + entry.getKey() + ", Type: " + entry.getValue().getInfo().toString());
        }
        for (NodeInfo input : decoderSession.getInputInfo().values()) {
            Log.d("ONNX-DecoderInput", "Name: " + input.getName() + ", Type: " + input.getInfo().toString());
        }

    }

    public synchronized String translate(String text) throws OrtException {
        if (!isTranslating.compareAndSet(false, true))
            throw new IllegalStateException("Already translating");
        stopRequested.set(false);
        try {
            // 1) tokenize + build encoder input
            int[] srcIds = spm.encodeAsIds(text);
            long[] encInput = new long[srcIds.length+3];
            encInput[0] = bosId;
            encInput[1] = srcLangTokenId;
            for (int i = 0; i < srcIds.length; i++) encInput[i+2] = srcIds[i];
            encInput[encInput.length-1] = eosId;

            OnnxTensor encTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(encInput),
                    new long[]{1, encInput.length});
            OnnxTensor encMask = createAttentionMask(encInput.length);

            // 2) run encoder
            OnnxTensor encHidden;
            try (OrtSession.Result out = encoderSession.run(Map.of(
                    "input_ids", encTensor,
                    "attention_mask", encMask))) {
                encHidden = (OnnxTensor) out.get(0);
            }

            // 3) greedy decode WITHOUT cache
            StringBuilder sb = new StringBuilder();
            int[] decInputIds = new int[]{bosId, tgtLangTokenId};
            for (int step=0; step<MAX_OUTPUT_LENGTH && !stopRequested.get(); step++) {
                long[] decIds = new long[decInputIds.length];
                for (int i=0; i<decInputIds.length; i++) decIds[i]=decInputIds[i];

                OnnxTensor decTensor = OnnxTensor.createTensor(env,
                        LongBuffer.wrap(decIds),
                        new long[]{1, decIds.length});

                OnnxTensor decMask = createAttentionMask(encInput.length);
                OrtSession.Result decOut = decoderSession.run(Map.of(
                        "input_ids", decTensor,
                        "encoder_hidden_states", encHidden,
                        "encoder_attention_mask", decMask
                ));
                float[][][] logits = (float[][][]) decOut.get(0).getValue();
                int next = argmax(logits[0][logits[0].length-1]);
                decOut.close();
                if (next == eosId) break;
                sb.append(spm.idToPiece(next)).append(" ");
                // append to decInputIds
                int[] tmp = new int[decInputIds.length+1];
                System.arraycopy(decInputIds, 0, tmp, 0, decInputIds.length);
                tmp[tmp.length-1] = next;
                decInputIds = tmp;
            }
            return sb.toString().trim();
        } finally {
            isTranslating.set(false);
        }
    }


    private OnnxTensor createAttentionMask(int len) throws OrtException {
        long[] m = new long[len];
        Arrays.fill(m, 1L);
        return OnnxTensor.createTensor(env, LongBuffer.wrap(m), new long[]{1, len});
    }

    private int argmax(float[] arr) {
        int idx=0;
        for(int i=1;i<arr.length;i++) if(arr[i]>arr[idx]) idx=i;
        return idx;
    }


    public void stopTranslate() {
        stopRequested.set(true);
    }

    @Override
    public void close() {
        stopTranslate();
        try {
            if (spm != null) spm.close();
        } catch (Exception ignored) {
        }
        try {
            if (encoderSession != null) encoderSession.close();
        } catch (Exception ignored) {
        }
        try {
            if (decoderSession != null) decoderSession.close();
        } catch (Exception ignored) {
        }
        env.close();
    }

    private File copyAsset(String path) throws IOException {
        File out = new File(ctx.getFilesDir(), path.replace('/', '_'));
        if (!out.exists()) {
            try (InputStream in = ctx.getAssets().open(path);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
            }
        }
        return out;
    }

    private String readStreamToString(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int len;
        while ((len = is.read(b)) != -1) baos.write(b, 0, len);
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    public static class TranslationException extends Exception {
        public TranslationException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
