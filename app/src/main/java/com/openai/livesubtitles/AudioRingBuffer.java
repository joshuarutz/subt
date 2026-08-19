package com.openai.livesubtitles;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

final class AudioRingBuffer {
    private final int sampleRate;
    private final int channels;
    private final int maxBytes;
    private final Deque<byte[]> buffers = new ArrayDeque<>();
    private int totalBytes = 0;

    AudioRingBuffer(int sampleRate, int channels, double seconds) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.maxBytes = (int) (sampleRate * channels * 2 * seconds);
    }

    synchronized void append(byte[] data) {
        buffers.addLast(data);
        totalBytes += data.length;
        while (!buffers.isEmpty() && totalBytes > maxBytes) {
            byte[] removed = buffers.removeFirst();
            totalBytes -= removed.length;
        }
    }

    synchronized byte[] snapshot() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(totalBytes);
        for (byte[] data : buffers) {
            out.write(data, 0, data.length);
        }
        return out.toByteArray();
    }

    synchronized double duration() {
        int divisor = sampleRate * channels * 2;
        return divisor == 0 ? 0.0 : (double) totalBytes / divisor;
    }

    static double rms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int lo = pcm[i] & 0xff;
            int hi = pcm[i + 1];
            short sample = (short) ((hi << 8) | lo);
            sum += (double) sample * sample;
            count++;
        }
        return count == 0 ? 0.0 : Math.sqrt(sum / count);
    }
}
