package com.xspaceagi.agent.core.infra.speech.asr;

import java.util.List;

public record AsrResult(
        String text,
        boolean ok,
        String error,
        Integer durationMs,
        List<AsrSentence> sentences) {

    public AsrResult {
        sentences = sentences == null ? List.of() : List.copyOf(sentences);
    }

    public static AsrResult success(String text) {
        return success(text, null, List.of());
    }

    public static AsrResult success(String text, Integer durationMs) {
        return success(text, durationMs, List.of());
    }

    public static AsrResult success(String text, Integer durationMs, List<AsrSentence> sentences) {
        return new AsrResult(text, true, null, durationMs, sentences);
    }

    public static AsrResult failure(String error) {
        return new AsrResult("", false, error, null, List.of());
    }
}
