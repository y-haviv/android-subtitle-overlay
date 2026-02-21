package com.example.subtitles.model.transcription.correction;


import android.util.Log;

import com.example.subtitles.model.transcription.correction.transcriptSegment;
import com.example.subtitles.model.transcription.correction.whisper.WhisperTranscriber;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.*;

/**
 * Singleton dictionary responsible for learning and applying
 * dynamic word-level corrections.
 *
 * The dictionary compares Vosk transcription segments against
 * Whisper transcription segments over short aligned time windows.
 * From repeated differences it learns reliable token replacements
 * and applies them later to improve Vosk output quality.
 *
 * Features:
 *  • Locale-aware tokenization
 *  • Time-window alignment
 *  • Edit-distance diffing
 *  • Score-based confidence
 *  • Automatic aging & eviction
 */
public class Dictionary {
    private static final String TAG = "Dictionary";

    /* ------------------------------------------------------------
     * Singleton
     * ------------------------------------------------------------ */
    private static volatile Dictionary instance;
    public static Dictionary getInstance() {
        if (instance == null) {
            synchronized (Dictionary.class) {
                if (instance == null) instance = new Dictionary();
            }
        }
        return instance;
    }

    /* ------------------------------------------------------------
     * Configuration
     * ------------------------------------------------------------ */

    /** Minimum observations required before a correction is trusted */
    private static final int MIN_SCORE = 2;
    /** Time (ms) before unused entries expire */
    private static final long MAX_AGE_MS = 5 * 60_000L;

    /** Maximum number of stored corrections */
    private static final int MAX_ENTRIES = 100;

    /** Sliding comparison window size */
    private static final long WINDOW_MS = 2000L;

    /* ------------------------------------------------------------
     * Internal storage (LRU-like)
     * ------------------------------------------------------------ */
    private final LinkedHashMap<String, Entry> map = createEvictingMap();

    private LinkedHashMap<String, Entry> createEvictingMap() {
        return new LinkedHashMap<String, Entry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Dictionary.Entry> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }


    private Dictionary() {}

    /* ------------------------------------------------------------
     * Learning Phase
     * ------------------------------------------------------------ */

    /**
     * Learns new corrections by comparing Vosk and Whisper
     * segments within aligned time windows.
     */
    public synchronized void addCorrections(
            String langCode,
            List<transcriptSegment> voskSegments,
            List<transcriptSegment> whisperSegments
    ) {
        final Set<String> seenWindows = new HashSet<>();
        Log.i(TAG, "addCorrections(): language=" + langCode +
                ", voskSegments=" + (voskSegments != null ? voskSegments.size() : 0) +
                ", whisperSegments=" + (whisperSegments != null ? whisperSegments.size() : 0));
        if (langCode == null || voskSegments == null || whisperSegments == null) {
            Log.w(TAG, "addCorrections(): aborted - null input");
            return;
        }

        if (voskSegments.isEmpty() || whisperSegments.isEmpty()) {
            Log.w(TAG, "addCorrections(): aborted - empty segment lists");
            return;
        }
        Locale loc = Locale.forLanguageTag(langCode);

        long startV = voskSegments.get(0).getStart();
        long startW = whisperSegments.get(0).getStart();
        long start = Math.max(startV, startW);
        Log.i(TAG, "🧩 Computed start time: " + start + "ms (VoskStart=" + startV + ", WhisperStart=" + startW + ")");

        long end = Math.max(
                voskSegments.get(voskSegments.size()-1).getEnd(),
                whisperSegments.get(whisperSegments.size()-1).getEnd()
        );
        if (end <= WhisperTranscriber.OVERLAP_SEC * 1000) {
            Log.i(TAG, "addCorrections(): insufficient data beyond overlap");
            return;
        }

        end -= (WhisperTranscriber.OVERLAP_SEC * 1000);
        Log.i(TAG, "🧩 Final end time after overlap adjustment: " + end + "ms");

        long now = System.currentTimeMillis();
        for (long w=start; w<end; w+=WINDOW_MS) {

            String a = joinBetween(voskSegments, w, w+WINDOW_MS);
            String b = joinBetween(whisperSegments, w, w+WINDOW_MS);
            String hash = normalize(a) + "###" + normalize(b);

            if (a.isEmpty() || b.isEmpty()) {
                continue;
            }

            if (seenWindows.contains(hash)) {
                continue;
            } else {
                seenWindows.clear();
            }
            seenWindows.add(hash);

            List<String> tokA = tokenize(a, loc);
            List<String> tokB = tokenize(b, loc);


            List<Correction> diffs = align(tokA, tokB);

            for (Correction c : diffs) {
                if (c.orig != null && c.exp != null) {
                    String key = normalize(c.orig);
                    map.compute(key, (k, e) -> {
                        if (e == null) {
                            Log.d(TAG, "Learned correction: \"" +
                                    c.orig + "\" -> \"" + c.exp + "\"");
                            return new Dictionary.Entry(c.exp, now);
                        } else {
                            return e.bump(now);
                        }
                    });
                }
            }
        }
        cleanup(now);
    }

    /* ------------------------------------------------------------
     * Application Phase
     * ------------------------------------------------------------ */

    /**
     * Applies learned corrections to a sentence.
     *
     * @return corrected sentence
     */
    public synchronized String getCorrection(String langCode, String seg) {
        if (seg == null || seg.isEmpty()) return "";
        Locale loc = Locale.forLanguageTag(langCode);
        List<String> toks = tokenize(seg, loc);
        String sep = isCJK(loc) ? "" : " ";
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (String t: toks) {
            Dictionary.Entry e = map.get(normalize(t));
            sb.append((e!=null && e.isValid(now))?e.corrected:t).append(sep);
        }

        if (sb.length()>=sep.length()) sb.setLength(sb.length()-sep.length());
        cleanup(now);
        return sb.toString();
    }

    /* ------------------------------------------------------------
     * Maintenance
     * ------------------------------------------------------------ */

    /** Clears all learned corrections */
    public synchronized void clear() {
        Log.i(TAG, "Dictionary cleared: all learned corrections removed.");
        map.clear();
    }

    /* ------------------------------------------------------------
     * Helpers
     * ------------------------------------------------------------ */
    private String joinBetween(List<transcriptSegment> segs, long from, long to) {
        StringBuilder sb = new StringBuilder();
        for (transcriptSegment s: segs) {
            if (s.getStart()<to && s.getEnd()>from) sb.append(s.getSentence()).append(' ');
        }
        return sb.toString().trim();
    }

    private List<String> tokenize(String text, Locale loc) {
        List<String> out=new ArrayList<>();
        BreakIterator it = isCJK(loc)
                ? BreakIterator.getCharacterInstance(loc)
                : BreakIterator.getWordInstance(loc);
        it.setText(text);
        int start=it.first(), end;
        while ((end=it.next())!=BreakIterator.DONE) {
            String w=text.substring(start,end).trim();
            if (!w.isEmpty()) out.add(w);
            start=end;
        }
        return out;
    }
    /**
     * Levenshtein alignment producing substitutions,
     * insertions and deletions.
     */
    private List<Correction> align(List<String>A,List<String>B){
        int n=A.size(),m=B.size();
        int[][]dp=new int[n+1][m+1];
        for(int i=0;i<=n;i++)dp[i][0]=i;
        for(int j=0;j<=m;j++)dp[0][j]=j;
        for(int i=1;i<=n;i++)for(int j=1;j<=m;j++){
            dp[i][j]=A.get(i-1).equalsIgnoreCase(B.get(j-1))
                    ? dp[i-1][j-1]
                    : 1+Math.min(dp[i-1][j-1],Math.min(dp[i-1][j],dp[i][j-1]));
        }
        List<Correction>res=new ArrayList<>();int i=n,j=m;
        while(i>0&&j>0){
            if(A.get(i-1).equalsIgnoreCase(B.get(j-1))){i--;j--;}
            else if(dp[i][j]==dp[i-1][j-1]+1){res.add(new Correction(A.get(i-1),B.get(j-1)));i--;j--;}
            else if(dp[i][j]==dp[i-1][j]+1){res.add(new Correction(A.get(--i),null));}
            else {res.add(new Correction(null,B.get(--j)));}
        }
        while(i>0)res.add(new Correction(A.get(--i),null));
        while(j>0)res.add(new Correction(null,B.get(--j)));
        Collections.reverse(res);return res;
    }

    private void cleanup(long now){
        Iterator<Map.Entry<String, Entry>> iter = map.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Entry> entry = iter.next();
            if (!entry.getValue().isValid(now)) {
                String reason = entry.getValue().score < MIN_SCORE
                        ? "low score (" + entry.getValue().score + ")"
                        : "expired (" + (now - entry.getValue().last)/1000 + "s ago)";
                System.out.println("[Entry Removed] \"" + entry.getKey() + "\" → \"" + entry.getValue().corrected + "\" — " + reason);
                iter.remove();
            }
        }

        //map.values().removeIf(e->!e.isValid(now));
    }

    private String normalize(String s){
        String t=Normalizer.normalize(s,Normalizer.Form.NFKC).replaceAll("\\p{M}","");
        return t.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isCJK(Locale loc){
        return Set.of("zh", "ja", "th", "lo", "km", "my", "bo").contains(loc.getLanguage());
    }

    /* ------------------------------------------------------------
     * Internal Types
     * ------------------------------------------------------------ */
    private static class Correction{final String orig,exp;Correction(String o,String e){orig=o;exp=e;}}
    private static class Entry{
        final String corrected;int score;long last;
        Entry(String c,long now){corrected=c;score=1;last=now;}
        Entry bump(long now){score++;last=now;return this;}
        boolean isValid(long now){return score>=MIN_SCORE && now-last<=MAX_AGE_MS;}
    }
}
