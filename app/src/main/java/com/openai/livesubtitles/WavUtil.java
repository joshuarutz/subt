package com.openai.livesubtitles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class WavUtil {
    static byte[] pcmToWav(byte[] pcm, int sampleRate, int channels) throws IOException {
        int dataLength = pcm.length;
        int byteRate = sampleRate * channels * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(dataLength + 44);

        writeAscii(out, "RIFF");
        writeIntLE(out, 36 + dataLength);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, 1);
        writeShortLE(out, channels);
        writeIntLE(out, sampleRate);
        writeIntLE(out, byteRate);
        writeShortLE(out, channels * 2);
        writeShortLE(out, 16);
        writeAscii(out, "data");
        writeIntLE(out, dataLength);
        out.write(pcm);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }
}
