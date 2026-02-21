package com.example.subtitles.archive.wav_handling;

import java.io.*;

public class WavFileWriter {
    public static void saveAsWav(File file, short[] data, int sampleRate, int channels) throws IOException {
        int byteRate = sampleRate * channels * 2;

        try (FileOutputStream fos = new FileOutputStream(file);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(bos)) {

            int totalAudioLen = data.length * 2;
            int totalDataLen = totalAudioLen + 36;

            // WAV Header
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(totalDataLen));
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16));
            dos.writeShort(Short.reverseBytes((short) 1)); // PCM
            dos.writeShort(Short.reverseBytes((short) channels));
            dos.writeInt(Integer.reverseBytes(sampleRate));
            dos.writeInt(Integer.reverseBytes(byteRate));
            dos.writeShort(Short.reverseBytes((short) (channels * 2)));
            dos.writeShort(Short.reverseBytes((short) 16));
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(totalAudioLen));

            // Write PCM Data
            for (short s : data) {
                dos.writeShort(Short.reverseBytes(s));
            }
        }
    }
}
