package com.example.subtitles.archive.wav_handling;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavReader {
    /** Very naive: assumes 16-bit PCM, no extra chunks, no compression */
    public static short[] read16bitMono(File wavFile) throws IOException {
        try (FileInputStream in = new FileInputStream(wavFile)) {
            byte[] header = new byte[44];
            if (in.read(header) != 44) throw new IOException("Invalid WAV header");
            int dataSize = ByteBuffer.wrap(header, 40, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
            short[] samples = new short[dataSize / 2];
            byte[] buf = new byte[2048];
            int offset = 0;
            int read;
            while ((read = in.read(buf)) > 0) {
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, read)
                        .order(ByteOrder.LITTLE_ENDIAN);
                while (bb.remaining() >= 2 && offset < samples.length) {
                    samples[offset++] = bb.getShort();
                }
            }
            return samples;
        }
    }
}
