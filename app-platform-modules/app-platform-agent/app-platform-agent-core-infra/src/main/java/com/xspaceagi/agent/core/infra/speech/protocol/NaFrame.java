package com.xspaceagi.agent.core.infra.speech.protocol;

public record NaFrame(
        int version,
        int codec,
        int flags,
        int channels,
        int sampleRate,
        int frameDurationMs,
        long sequence,
        long timestampMs,
        byte[] payload) {
}
