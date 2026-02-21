package com.example.subtitles.archive.speaker_detection;

import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jtransforms.fft.FloatFFT_1D;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;

/**
 * Utility class for extracting log-Mel filterbank features.
 *
 * <p>
 * Pipeline:
 * <ol>
 *   <li>Normalize PCM</li>
 *   <li>STFT (FFT)</li>
 *   <li>Apply Mel filterbank</li>
 *   <li>Log compression</li>
 *   <li>Utterance-level CMVN</li>
 * </ol>
 *
 * Output shape: [1, n_mels, T]
 */

public class FeatureUtils {
    private static final String TAG = "FeatureUtils";
    private static final int FRAME_SIZE = 400;   // 25 ms @16 kHz
    private static final int HOP_SIZE   = 160;   // 10 ms hop
    private static final int FFT_BINS   = FRAME_SIZE/2 + 1;

    private static float[][] melFilters;
    private static int      nMels = -1;
    private static FloatFFT_1D fft;
    private static final Object lock = new Object();
    private static float[] window;

    public static void init(Context ctx) {
        try (InputStream is = ctx.getAssets().open("mel_filters.json")) {
            Type type = new TypeToken<float[][]>(){}.getType();
            melFilters = new Gson().fromJson(new InputStreamReader(is), type);
            nMels = melFilters.length;
            if (melFilters[0].length != FFT_BINS) {
                throw new IllegalStateException(
                        "FFT_BINS mismatch: expected "+FFT_BINS+" got "+melFilters[0].length);
            }
            fft    = new FloatFFT_1D(FRAME_SIZE);
            window = hannWindow(FRAME_SIZE);
            Log.i(TAG, "Loaded melFilters: n_mels="+nMels+", fft_bins="+FFT_BINS);
        } catch (Exception e) {
            Log.e(TAG, "Failed to init FeatureUtils", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert raw PCM [short[]] → [1, n_mels, T] float log‑Mel, with per‑band CMVN.
     */
    public static float[][][] extractLogMel(short[] pcmWindow) {
        if (melFilters==null) throw new IllegalStateException("call init() first");

        // 1) normalize + pad + hop‑alignment
        int N = pcmWindow.length;
        float[] signal = new float[Math.max(N, FRAME_SIZE)];
        for (int i = 0; i < N; i++) signal[i] = pcmWindow[i]/32768f;
        int padded = signal.length;
        int rem = (padded - FRAME_SIZE) % HOP_SIZE;
        if (rem!=0) {
            float[] tmp = new float[padded + (HOP_SIZE-rem)];
            System.arraycopy(signal,0,tmp,0,padded);
            signal = tmp; padded = tmp.length;
        }
        int T = 1 + (padded - FRAME_SIZE)/HOP_SIZE;
        float[][] melSpec = new float[T][nMels];

        // 2) STFT‑mag → Mel
        float[] buf  = new float[FRAME_SIZE];
        float[] spec = new float[FFT_BINS];
        for (int t=0; t<T; t++) {
            int off = t*HOP_SIZE;
            for (int i=0; i<FRAME_SIZE; i++) {
                buf[i] = ((off+i<signal.length)?signal[off+i]:0f) * window[i];
            }
            synchronized(lock){ fft.realForward(buf); }
            spec[0] = Math.abs(buf[0]);
            for (int k=1; k<FFT_BINS-1; k++)
                spec[k]=(float)Math.hypot(buf[2*k],buf[2*k+1]);
            spec[FFT_BINS-1]=Math.abs(buf[1]);
            for (int m=0; m<nMels; m++){
                float sum=0;
                float[] filt = melFilters[m];
                for(int k=0;k<FFT_BINS;k++) sum+=filt[k]*spec[k];
                melSpec[t][m] = (float)Math.log(sum+1e-6f);
            }
        }

        // 3) utterance‑level CMVN
        float[] mean = new float[nMels], var = new float[nMels];
        for (int m=0; m<nMels; m++) {
            for (int t=0; t<T; t++) mean[m]+=melSpec[t][m];
            mean[m]/=T;
            for (int t=0; t<T; t++){
                float d = melSpec[t][m]-mean[m];
                var[m]+=d*d;
            }
            var[m] = (float)Math.sqrt(var[m]/T + 1e-9f);
        }
        for (int t=0; t<T; t++){
            for(int m=0;m<nMels;m++){
                melSpec[t][m] = (melSpec[t][m]-mean[m])/var[m];
            }
        }

        // 4) reshape to [1,n_mels,T]
        float[][][] out = new float[1][nMels][T];
        for (int m=0; m<nMels; m++)
            for (int t=0; t<T; t++)
                out[0][m][t] = melSpec[t][m];

        Log.d(TAG, String.format("Feature shape: [1,%d,%d]", nMels, T));
        return out;
    }

    private static float[] hannWindow(int N) {
        float[] w = new float[N];
        for (int i=0;i<N;i++)
            w[i] = 0.5f*(1f - (float)Math.cos(2*Math.PI*i/(N-1)));
        return w;
    }
}
