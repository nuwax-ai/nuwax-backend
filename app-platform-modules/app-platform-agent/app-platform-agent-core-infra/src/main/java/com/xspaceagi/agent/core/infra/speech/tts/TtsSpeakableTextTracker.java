package com.xspaceagi.agent.core.infra.speech.tts;

/**
 * 在 Agent 流式输出过程中，把原始增量文本转为可朗读增量，避免 Markdown/HTML 被 TTS 逐字读出。
 */
public final class TtsSpeakableTextTracker {

    private final StringBuilder raw = new StringBuilder();
    private String lastSpeakable = "";

    public void reset() {
        raw.setLength(0);
        lastSpeakable = "";
    }

    public String feedRawDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        raw.append(delta);
        return pullSpeakableDelta(false);
    }

    public String feedRawFullText(String fullText) {
        reset();
        if (fullText == null || fullText.isBlank()) {
            return "";
        }
        raw.append(fullText);
        return pullSpeakableDelta(true);
    }

    public String appendFinalText(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return flushRemainder();
        }
        return emitSpeakableTail(TtsTextSanitizer.sanitize(fullText));
    }

    public String flushRemainder() {
        return pullSpeakableDelta(true);
    }

    /** 当前已确定、与 TTS 同步的可朗读全文（流式过程中可能随 raw 增长）。 */
    public String getSpeakableText() {
        if (raw.isEmpty()) {
            return lastSpeakable;
        }
        return TtsTextSanitizer.sanitize(raw.toString());
    }

    /** 用 Agent 原始全文刷新可朗读文本（用于收尾 text_response 与 TTS 对齐）。 */
    public void ensureSpeakableFromRaw(String fullText) {
        raw.setLength(0);
        if (fullText != null && !fullText.isBlank()) {
            raw.append(fullText);
        }
        pullSpeakableDelta(true);
    }

    private String pullSpeakableDelta(boolean flush) {
        if (!flush && TtsTextSanitizer.shouldHoldForIncompleteStructure(raw.toString())) {
            return "";
        }
        return emitSpeakableTail(TtsTextSanitizer.sanitize(raw.toString()));
    }

    private String emitSpeakableTail(String speakable) {
        if (speakable.isEmpty()) {
            lastSpeakable = "";
            return "";
        }
        if (lastSpeakable.isEmpty()) {
            lastSpeakable = speakable;
            return speakable;
        }
        if (speakable.equals(lastSpeakable)) {
            return "";
        }
        if (speakable.startsWith(lastSpeakable)) {
            String delta = speakable.substring(lastSpeakable.length());
            if (delta.isBlank() || repeatsSpeakableContent(delta, lastSpeakable)) {
                lastSpeakable = speakable;
                return "";
            }
            lastSpeakable = speakable;
            return delta;
        }
        if (lastSpeakable.startsWith(speakable)) {
            lastSpeakable = speakable;
            return "";
        }
        return "";
    }

    private static boolean repeatsSpeakableContent(String candidate, String spoken) {
        String left = normalizeForCompare(candidate);
        String right = normalizeForCompare(spoken);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.equals(right) || right.contains(left) || left.contains(right);
    }

    private static String normalizeForCompare(String text) {
        return text.replaceAll("[\\s\\p{P}]", "").toLowerCase();
    }
}
