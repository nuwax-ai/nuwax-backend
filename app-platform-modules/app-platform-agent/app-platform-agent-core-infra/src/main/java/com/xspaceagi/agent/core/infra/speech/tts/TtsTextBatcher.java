package com.xspaceagi.agent.core.infra.speech.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * 将流式可朗读增量合并后再喂 TTS，减少碎片段 {@code feed} 次数与空等。
 *
 * <p>触发刷出：句末/中文子句标点 / 达到 {@code minChars} / 距首字超过 {@code maxHoldMs} / 强制 flush。
 * 中文子句标点（，、：）切分以避免连读；不含 ASCII {@code ,:}，以免拆开 {@code 1,234}、{@code 12:30}、URL。
 */
public final class TtsTextBatcher {

    /** 句末标点：强制切分。 */
    private static final String SENTENCE_END = "。！？；.!?;\n";
    /** 中文子句标点：切分以保留停顿；不含 ASCII ,:（数字/时间/URL）。 */
    private static final String CLAUSE_BREAK = "，、：";

    private final int minChars;
    private final long maxHoldMs;
    private final StringBuilder pending = new StringBuilder();
    private long pendingSinceNs;

    public TtsTextBatcher(int minChars, long maxHoldMs) {
        this.minChars = Math.max(1, minChars);
        this.maxHoldMs = Math.max(0L, maxHoldMs);
    }

    /**
     * @param delta 可朗读增量；可为空
     * @param force 强制吐出全部缓冲（turn 收尾）
     * @return 本轮应送入 TTS 的文本段（通常 0～1 段）
     */
    public synchronized List<String> accept(String delta, boolean force) {
        if (delta != null && !delta.isEmpty()) {
            if (pending.length() == 0) {
                pendingSinceNs = System.nanoTime();
            }
            pending.append(delta);
        }
        List<String> out = new ArrayList<>(2);
        if (force) {
            drainAll(out);
            return out;
        }
        while (true) {
            String ready = pullReadyLocked();
            if (ready == null) {
                break;
            }
            out.add(ready);
        }
        return out;
    }

    /** 当前缓冲字数（排障用）。 */
    public synchronized int pendingChars() {
        return pending.length();
    }

    private String pullReadyLocked() {
        if (pending.length() == 0) {
            return null;
        }
        int endIdx = indexOfProsodyBreak(pending);
        if (endIdx >= 0) {
            String chunk = pending.substring(0, endIdx + 1);
            pending.delete(0, endIdx + 1);
            resetClockIfEmpty();
            return chunk;
        }
        boolean heldLongEnough = maxHoldMs > 0
                && pendingSinceNs > 0
                && (System.nanoTime() - pendingSinceNs) / 1_000_000L >= maxHoldMs;
        if (pending.length() >= minChars || heldLongEnough) {
            String chunk = pending.toString();
            pending.setLength(0);
            pendingSinceNs = 0L;
            return chunk;
        }
        return null;
    }

    private void drainAll(List<String> out) {
        if (pending.length() > 0) {
            out.add(pending.toString());
            pending.setLength(0);
            pendingSinceNs = 0L;
        }
    }

    private void resetClockIfEmpty() {
        if (pending.length() == 0) {
            pendingSinceNs = 0L;
        } else {
            pendingSinceNs = System.nanoTime();
        }
    }

    private static int indexOfProsodyBreak(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (SENTENCE_END.indexOf(c) >= 0 || CLAUSE_BREAK.indexOf(c) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
