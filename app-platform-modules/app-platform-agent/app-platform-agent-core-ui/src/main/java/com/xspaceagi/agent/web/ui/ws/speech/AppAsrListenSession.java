package com.xspaceagi.agent.web.ui.ws.speech;

import com.xspaceagi.agent.core.infra.speech.asr.AsrListener;
import com.xspaceagi.agent.core.infra.speech.asr.AsrResult;
import com.xspaceagi.agent.core.infra.speech.asr.AsrSentence;
import com.xspaceagi.agent.core.infra.speech.asr.AsrService;
import com.xspaceagi.agent.core.infra.speech.asr.AsrSessionFactory;
import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.protocol.NaFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App ASR 听音会话：流式字幕推送 + 百炼空闲超时静默重建（对齐 vox DeviceConversationService）。
 */
final class AppAsrListenSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppAsrListenSession.class);

    private final WebSocketSession webSocketSession;
    private final AppWsMessageSender messageSender;
    private final AsrSessionFactory asrSessionFactory;
    private final SpeechProperties.Asr asrConfig;
    private final AudioParams audioParams;
    private final AtomicLong sequence = new AtomicLong(0);
    private final Object lock = new Object();
    private final ArrayDeque<NaFrame> replayFrames = new ArrayDeque<>();
    private int replayBufferedMs;
    private final List<AsrSentence> carriedSentences = new ArrayList<>();
    private final StringBuilder carriedText = new StringBuilder();
    private final ExecutorService rebuildExecutor;
    private final ExecutorService stopExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean stopSent = new AtomicBoolean();
    private final AtomicBoolean rebuildInProgress = new AtomicBoolean();
    private final AtomicInteger rebuildAttempts = new AtomicInteger();
    private final AtomicLong listenGeneration = new AtomicLong(1);
    private final AtomicReference<AsrService> asrRef = new AtomicReference<>();
    private final Runnable onDetached;
    private volatile Integer latestDurationMs;

    AppAsrListenSession(WebSocketSession webSocketSession, AppWsMessageSender messageSender,
            AsrSessionFactory asrSessionFactory, SpeechProperties.Asr asrConfig, AudioParams audioParams,
            AsrService asr, Runnable onDetached) {
        this.webSocketSession = webSocketSession;
        this.messageSender = messageSender;
        this.asrSessionFactory = asrSessionFactory;
        this.asrConfig = asrConfig;
        this.audioParams = audioParams;
        this.onDetached = onDetached;
        this.asrRef.set(asr);
        String id = webSocketSession.getId();
        this.rebuildExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-asr-rebuild-" + id);
            t.setDaemon(true);
            return t;
        });
        this.stopExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-asr-stop-" + id);
            t.setDaemon(true);
            return t;
        });
        bindListener(asr, listenGeneration.get());
    }

    void feed(byte[] pcm) {
        if (closed.get() || stopRequested.get() || pcm == null || pcm.length == 0) {
            return;
        }
        NaFrame frame = AppPcmAudioSupport.pcm16Frame(
                pcm,
                audioParams.uplinkSampleRate(),
                audioParams.frameDurationMs(),
                sequence.getAndIncrement());
        AsrService asr;
        synchronized (lock) {
            if (closed.get() || stopRequested.get()) {
                return;
            }
            // 对齐 vox：仅重建空窗缓冲上行；常态不写 replay，避免清标志后二次 drain 双喂
            if (rebuildInProgress.get()) {
                offerReplay(frame);
                return;
            }
            asr = asrRef.get();
        }
        if (asr == null) {
            return;
        }
        asr.feedFrame(frame);
    }

    void requestStop() {
        if (!stopRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            stopExecutor.execute(this::doStop);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            log.warn("App ASR stop rejected session={}: {}", webSocketSession.getId(), ex.getMessage());
            sendStopResult(AsrResult.failure("asr_stop_rejected"));
            close();
        }
    }

    private void doStop() {
        waitRebuildIdle(3_000);
        AsrService asr = asrRef.getAndSet(null);
        AsrResult vendorResult;
        if (asr == null) {
            vendorResult = AsrResult.failure("asr_not_started");
        } else {
            try {
                vendorResult = asr.flush();
            } catch (Exception ex) {
                log.warn("App ASR flush failed session={}: {}", webSocketSession.getId(), ex.getMessage());
                vendorResult = AsrResult.failure(ex.getMessage() == null ? "asr_failed" : ex.getMessage());
            }
        }
        AsrResult merged = mergeWithCarried(vendorResult);
        sendStopResult(merged);
        close();
    }

    private AsrResult mergeWithCarried(AsrResult vendorResult) {
        synchronized (lock) {
            String carried = carriedText.toString();
            List<AsrSentence> sentences = new ArrayList<>(carriedSentences);
            Integer durationMs = latestDurationMs;
            if (vendorResult != null && vendorResult.durationMs() != null) {
                durationMs = vendorResult.durationMs();
            }
            if (vendorResult != null && vendorResult.ok()) {
                // listener 已把定稿句写入 carried；同会话 vendor flush 取前缀增量；
                // 重建后 vendor 仅为新会话全文（与 carried 无前缀关系）则整段追加。
                String vendorText = vendorResult.text() == null ? "" : vendorResult.text().trim();
                String extra = resolveVendorExtra(carried, vendorText);
                String assembled = (carried + extra).trim();
                if (assembled.isEmpty()) {
                    assembled = vendorText;
                    if (vendorResult.sentences() != null && !vendorResult.sentences().isEmpty()) {
                        sentences = new ArrayList<>(vendorResult.sentences());
                    }
                } else if (!extra.isEmpty()) {
                    sentences.add(new AsrSentence(extra, null, null));
                }
                if (assembled.isEmpty()) {
                    return AsrResult.failure("no_speech");
                }
                return AsrResult.success(assembled, durationMs, List.copyOf(sentences));
            }
            String carriedTrimmed = carried.trim();
            if (!carriedTrimmed.isEmpty()) {
                String err = vendorResult != null && !vendorResult.ok() ? vendorResult.error() : null;
                if (err != null && !err.isBlank()) {
                    log.warn("App ASR flush vendor failed but carried text present session={} error={}",
                            webSocketSession.getId(), err);
                    // 流已失败：保留已识别文本，但 ok=false，避免前端只看 ok 误判成功
                    return new AsrResult(carriedTrimmed, false, err, durationMs, List.copyOf(sentences));
                }
                return AsrResult.success(carriedTrimmed, durationMs, List.copyOf(sentences));
            }
            if (vendorResult != null) {
                return vendorResult;
            }
            return AsrResult.failure("no_speech");
        }
    }

    /**
     * 同会话：取 {@code full} 相对 {@code committed} 的前缀增量。
     * 跨重建：二者无前缀关系时返回整段 {@code full}（避免丢新会话文本）。
     */
    private static String resolveVendorExtra(String committed, String full) {
        if (full == null || full.isBlank()) {
            return "";
        }
        String fullTrim = full.trim();
        if (committed == null || committed.isEmpty()) {
            return fullTrim;
        }
        String prefixExtra = incrementalBeyond(committed, fullTrim);
        if (!prefixExtra.isEmpty()) {
            return prefixExtra;
        }
        String committedTrim = committed.trim();
        if (fullTrim.equals(committedTrim) || committedTrim.endsWith(fullTrim)) {
            return "";
        }
        // 重建后：carried=上代全文，vendor=当前会话全文
        return fullTrim;
    }

    /** {@code full} 相对已定稿 {@code committed} 的前缀增量；无前缀关系时返回空。 */
    private static String incrementalBeyond(String committed, String full) {
        if (full == null || full.isBlank()) {
            return "";
        }
        String fullTrim = full.trim();
        if (committed == null || committed.isEmpty()) {
            return fullTrim;
        }
        if (fullTrim.startsWith(committed)) {
            return fullTrim.substring(committed.length()).trim();
        }
        String committedTrim = committed.trim();
        if (!committedTrim.isEmpty() && fullTrim.startsWith(committedTrim)) {
            return fullTrim.substring(committedTrim.length()).trim();
        }
        return "";
    }

    private void sendStopResult(AsrResult result) {
        if (!stopSent.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "asr_result");
            payload.put("text", result.text() == null ? "" : result.text());
            payload.put("ok", result.ok());
            if (result.durationMs() != null) {
                payload.put("duration_ms", result.durationMs());
            }
            if (!result.ok() && result.error() != null) {
                payload.put("error", result.error());
            }
            List<Map<String, Object>> sentences = new ArrayList<>();
            for (AsrSentence sentence : result.sentences()) {
                Map<String, Object> item = new HashMap<>();
                item.put("text", sentence.text());
                if (sentence.beginMs() != null) {
                    item.put("begin_ms", sentence.beginMs());
                }
                if (sentence.endMs() != null) {
                    item.put("end_ms", sentence.endMs());
                }
                sentences.add(item);
            }
            if (!sentences.isEmpty()) {
                payload.put("sentences", sentences);
            }
            messageSender.sendJson(webSocketSession, payload);
        } catch (Exception ex) {
            log.warn("App ASR send result failed session={}: {}", webSocketSession.getId(), ex.getMessage());
        }
    }

    private void bindListener(AsrService asr, long generation) {
        asr.setListener(new AsrListener() {
            @Override
            public void onPartial(String text) {
                if (!isCurrent(generation) || stopRequested.get()) {
                    return;
                }
                pushPartial(text);
            }

            @Override
            public void onSentenceFinal(AsrSentence sentence) {
                if (!isCurrent(generation) || stopRequested.get()) {
                    return;
                }
                carrySentence(sentence);
                if (rebuildAttempts.get() > 0
                        && sentence != null && sentence.text() != null && !sentence.text().isBlank()) {
                    // 识别有真实进展 = 会话已恢复，重置静默重建额度
                    rebuildAttempts.set(0);
                }
                pushSentence(sentence);
            }

            @Override
            public void onError(String error) {
                if (!isCurrent(generation) || stopRequested.get() || closed.get()) {
                    return;
                }
                onAsrError(asr, generation, error);
            }
        });
    }

    private boolean isCurrent(long generation) {
        return listenGeneration.get() == generation && asrRef.get() != null;
    }

    private void carrySentence(AsrSentence sentence) {
        if (sentence == null || sentence.text() == null || sentence.text().isBlank()) {
            return;
        }
        synchronized (lock) {
            carriedText.append(sentence.text());
            carriedSentences.add(sentence);
            if (sentence.endMs() != null) {
                latestDurationMs = sentence.endMs();
            }
        }
    }

    private void pushPartial(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            messageSender.sendJson(webSocketSession, Map.of(
                    "type", "asr_partial",
                    "text", text));
        } catch (Exception ex) {
            log.debug("App ASR partial push failed session={}: {}", webSocketSession.getId(), ex.getMessage());
        }
    }

    private void pushSentence(AsrSentence sentence) {
        if (sentence == null || sentence.text() == null || sentence.text().isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "asr_sentence");
            payload.put("text", sentence.text());
            if (sentence.beginMs() != null) {
                payload.put("begin_ms", sentence.beginMs());
            }
            if (sentence.endMs() != null) {
                payload.put("end_ms", sentence.endMs());
            }
            messageSender.sendJson(webSocketSession, payload);
        } catch (Exception ex) {
            log.debug("App ASR sentence push failed session={}: {}", webSocketSession.getId(), ex.getMessage());
        }
    }

    private void onAsrError(AsrService failedAsr, long generation, String error) {
        String code = error == null || error.isBlank() ? "asr_error" : error;
        if (tryScheduleSilentRebuild(failedAsr, generation, code)) {
            return;
        }
        if (stopRequested.get() || closed.get()) {
            return;
        }
        failListenAndEnd(code);
    }

    private boolean tryScheduleSilentRebuild(AsrService failedAsr, long generation, String code) {
        if (!asrConfig.isTimeoutSilentRebuild() || !isRecoverableAsrTimeout(failedAsr, code)) {
            return false;
        }
        if (stopRequested.get() || closed.get()) {
            return false;
        }
        int maxAttempts = Math.max(1, asrConfig.getRebuildMaxAttempts());
        if (rebuildAttempts.get() >= maxAttempts) {
            log.warn("App ASR silent rebuild exhausted attempts={}/{} session={}",
                    rebuildAttempts.get(), maxAttempts, webSocketSession.getId());
            return false;
        }
        synchronized (lock) {
            if (listenGeneration.get() != generation || asrRef.get() != failedAsr) {
                return false;
            }
            if (rebuildInProgress.get()) {
                return true;
            }
            rebuildInProgress.set(true);
            asrRef.compareAndSet(failedAsr, null);
            clearReplay();
        }
        rebuildAttempts.incrementAndGet();
        closeQuietly(failedAsr);
        log.info("App ASR idle timeout, silent rebuild session={} attempt={}/{} error={}",
                webSocketSession.getId(), rebuildAttempts.get(), maxAttempts, code);
        try {
            rebuildExecutor.execute(() -> silentRebuild(generation));
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            synchronized (lock) {
                rebuildInProgress.set(false);
            }
            log.warn("App ASR rebuild rejected session={}: {}", webSocketSession.getId(), ex.getMessage());
            return false;
        }
        return true;
    }

    private void silentRebuild(long oldGeneration) {
        AsrService newAsr = null;
        try {
            if (closed.get() || stopRequested.get() || listenGeneration.get() != oldGeneration) {
                return;
            }
            newAsr = asrSessionFactory.createSession(audioParams);
            AtomicReference<String> prepareError = new AtomicReference<>();
            newAsr.setListener(new AsrListener() {
                @Override
                public void onError(String error) {
                    prepareError.compareAndSet(null, error == null || error.isBlank() ? "asr_error" : error);
                }
            });
            newAsr.prepare();
            if (prepareError.get() != null) {
                closeQuietly(newAsr);
                newAsr = null;
                failListenAndEnd(prepareError.get());
                return;
            }
            if (closed.get() || stopRequested.get() || listenGeneration.get() != oldGeneration) {
                closeQuietly(newAsr);
                return;
            }
            long newGeneration = listenGeneration.incrementAndGet();
            AsrService installed;
            synchronized (lock) {
                // 先挂 asrRef，再绑 listener，避免 isCurrent 因 ref==null 丢掉极早回调
                asrRef.set(newAsr);
                installed = newAsr;
                newAsr = null;
            }
            bindListener(installed, newGeneration);
            // 仍保持 rebuildInProgress：上行只进缓冲，先回放再开放 live feed（对齐 vox）
            int replayed = drainReplay(installed);
            synchronized (lock) {
                rebuildInProgress.set(false);
            }
            // finish 与并发上行之间的竞态帧：再冲一次（此时已不写 replay，不会双喂）
            replayed += drainReplay(installed);
            log.info("App ASR silent rebuild ok session={} attempt={} replayFrames={}",
                    webSocketSession.getId(), rebuildAttempts.get(), replayed);
        } catch (Exception ex) {
            log.warn("App ASR silent rebuild failed session={}", webSocketSession.getId(), ex);
            closeQuietly(newAsr);
            if (!stopRequested.get() && !closed.get()) {
                failListenAndEnd("asr_timeout");
            }
        } finally {
            synchronized (lock) {
                rebuildInProgress.set(false);
            }
        }
    }

    /** 重建/流失败：推 error + 尽量回已有文本，并拆掉听音属性，避免继续吞音频。 */
    private void failListenAndEnd(String code) {
        if (closed.get()) {
            return;
        }
        stopRequested.set(true);
        AsrService asr = asrRef.getAndSet(null);
        closeQuietly(asr);
        String detail = code == null || code.isBlank() ? "asr_error" : code;
        messageSender.sendError(webSocketSession, "ASR_STREAM_FAILED", detail);
        AsrResult merged = mergeWithCarried(AsrResult.failure(detail));
        sendStopResult(merged);
        detachFromWebSocket();
        close();
    }

    private void detachFromWebSocket() {
        if (onDetached == null) {
            return;
        }
        try {
            onDetached.run();
        } catch (Exception ex) {
            log.debug("App ASR detach callback failed session={}: {}", webSocketSession.getId(), ex.getMessage());
        }
    }

    private int drainReplay(AsrService target) {
        if (target == null) {
            return 0;
        }
        List<NaFrame> batch;
        synchronized (lock) {
            batch = new ArrayList<>(replayFrames);
            clearReplay();
        }
        int n = 0;
        for (NaFrame frame : batch) {
            if (asrRef.get() != target || closed.get() || stopRequested.get()) {
                break;
            }
            target.feedFrame(frame);
            n++;
        }
        return n;
    }

    private void offerReplay(NaFrame frame) {
        int cap = Math.max(0, asrConfig.getRebuildReplayMs());
        if (cap <= 0 || frame == null || frame.payload() == null || frame.payload().length == 0) {
            return;
        }
        int frameMs = frame.frameDurationMs() > 0 ? frame.frameDurationMs() : 20;
        byte[] copy = Arrays.copyOf(frame.payload(), frame.payload().length);
        replayFrames.addLast(new NaFrame(
                frame.version(),
                frame.codec(),
                frame.flags(),
                frame.channels(),
                frame.sampleRate(),
                frame.frameDurationMs(),
                frame.sequence(),
                frame.timestampMs(),
                copy));
        replayBufferedMs += frameMs;
        while (replayBufferedMs > cap && !replayFrames.isEmpty()) {
            NaFrame oldest = replayFrames.removeFirst();
            int oldestMs = oldest.frameDurationMs() > 0 ? oldest.frameDurationMs() : 20;
            replayBufferedMs = Math.max(0, replayBufferedMs - oldestMs);
        }
    }

    private void clearReplay() {
        replayFrames.clear();
        replayBufferedMs = 0;
    }

    private void waitRebuildIdle(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (rebuildInProgress.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 优先按连接状态判定（服务端在识别结束前断开即可恢复），
     * 避免依赖 provider 原始错误消息的关键字匹配。
     */
    private static boolean isRecoverableAsrTimeout(AsrService failedAsr, String code) {
        if (failedAsr != null && failedAsr.isIdleDisconnected()) {
            return true;
        }
        return isRecoverableAsrTimeout(code);
    }

    private static boolean isRecoverableAsrTimeout(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String trimmed = code.trim();
        // 空闲断连未附带 task-failed 时即此码；task-start 超时多为建连抖动，均可换会话恢复
        if ("asr_timeout".equals(trimmed) || "asr_connection_closed".equals(trimmed)
                || "asr_task_start_timeout".equals(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase();
        return lower.contains("request timeout") || lower.contains("timed out")
                || lower.contains("idle timeout");
    }

    private static void closeQuietly(AsrService asr) {
        if (asr == null) {
            return;
        }
        try {
            asr.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopRequested.set(true);
        AsrService asr = asrRef.getAndSet(null);
        closeQuietly(asr);
        rebuildExecutor.shutdownNow();
        stopExecutor.shutdownNow();
    }
}
