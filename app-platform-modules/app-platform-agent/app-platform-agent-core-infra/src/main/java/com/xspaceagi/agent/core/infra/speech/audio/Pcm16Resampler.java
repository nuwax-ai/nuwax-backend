package com.xspaceagi.agent.core.infra.speech.audio;

/**
 * 简单线性插值，将 mono PCM16 LE 重采样到目标采样率。
 */
public final class Pcm16Resampler {

    private Pcm16Resampler() {
    }

    public static byte[] resample(byte[] pcm16MonoLe, int fromRate, int toRate) {
        if (pcm16MonoLe == null || pcm16MonoLe.length == 0 || fromRate <= 0 || toRate <= 0) {
            return pcm16MonoLe == null ? new byte[0] : pcm16MonoLe;
        }
        if (fromRate == toRate) {
            return pcm16MonoLe;
        }
        if ((pcm16MonoLe.length & 1) != 0) {
            throw new IllegalArgumentException("PCM16 payload length must be even");
        }
        int sampleCount = pcm16MonoLe.length / 2;
        short[] input = toShortArray(pcm16MonoLe, sampleCount);
        int outputCount = Math.max(1, (int) ((long) sampleCount * toRate / fromRate));
        short[] output = new short[outputCount];
        for (int i = 0; i < outputCount; i++) {
            double srcPos = (double) i * fromRate / toRate;
            int index = (int) srcPos;
            double fraction = srcPos - index;
            short left = input[Math.min(index, sampleCount - 1)];
            short right = input[Math.min(index + 1, sampleCount - 1)];
            output[i] = (short) Math.round(left + fraction * (right - left));
        }
        return toByteArray(output);
    }

    private static short[] toShortArray(byte[] pcm, int sampleCount) {
        short[] samples = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            samples[i] = (short) (lo | (hi << 8));
        }
        return samples;
    }

    private static byte[] toByteArray(short[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            pcm[i * 2] = (byte) (samples[i] & 0xFF);
            pcm[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return pcm;
    }
}
