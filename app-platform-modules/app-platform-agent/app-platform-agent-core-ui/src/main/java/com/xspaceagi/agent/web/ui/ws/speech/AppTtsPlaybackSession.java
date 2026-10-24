package com.xspaceagi.agent.web.ui.ws.speech;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.tts.AbstractPcmTtsStream;
import com.xspaceagi.agent.core.infra.speech.tts.TtsService;
import com.xspaceagi.agent.core.infra.speech.tts.TtsStream;
import com.xspaceagi.agent.core.infra.speech.tts.TtsTextBatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App TTS 播报会话：文本合并 → 异步喂字/拉 PCM → 预缓冲限速下发，对齐 vox StreamingPlayback。
 * 含百炼 ~23s 空闲超时的主动保活、静默重建（额度按喂字进展重置）与 finish 超时降级。
 */
final class AppTtsPlaybackSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppTtsPlaybackSession.class);

    private final WebSocketSession webSocketSession;
    private final AppWsMessageSender messageSender;
    private final TtsService ttsService;
    private final SpeechProperties.Tts ttsConfig;
    private final AudioParams audioParams;
    private final TtsTextBatcher batcher;
    private final LinkedBlockingQueue<FeedJob> queue = new LinkedBlockingQueue<>();
    private final ArrayDeque<byte[]> outboundFrames = new ArrayDeque<>();
    private final Object outboundLock = new Object();
    private final Object speakableLock = new Object();
    private final Object ttsLock = new Object();
    private final ExecutorService feedExecutor;
    private final ExecutorService senderExecutor;
    private final ExecutorService flushExecutor;
    private final AtomicBoolean workersStarted = new AtomicBoolean();
    private final AtomicBoolean finishRequested = new AtomicBoolean();
    private final AtomicBoolean flushSubmitted = new AtomicBoolean();
    private final AtomicBoolean flushProcessed = new AtomicBoolean();
    private final AtomicBoolean feedLoopExited = new AtomicBoolean();
    private final AtomicBoolean begun = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean endSignaled = new AtomicBoolean();
    private final AtomicBoolean errorSignaled = new AtomicBoolean();
    private final AtomicInteger framesSentCounter = new AtomicInteger();

    private volatile TtsStream ttsStream;
    private volatile String ttsError;
    private volatile boolean feedBusy;
    private volatile boolean senderBusy;
    private int pacedContentFrames;
    private int ttsRebuildAttempts;
    private long lastSendNanos = -1L;
    private long lastFeedNanos;

    AppTtsPlaybackSession(WebSocketSession webSocketSession, AppWsMessageSender messageSender,
            TtsService ttsService, SpeechProperties.Tts ttsConfig, AudioParams audioParams, TtsStream ttsStream) {
        this.webSocketSession = webSocketSession;
        this.messageSender = messageSender;
        this.ttsService = ttsService;
        this.ttsConfig = ttsConfig;
        this.audioParams = audioParams;
        this.ttsStream = ttsStream;
        this.batcher = new TtsTextBatcher(ttsConfig.getBatchMinChars(), ttsConfig.getBatchMaxHoldMs());
        String id = webSocketSession.getId();
        this.feedExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-tts-feed-" + id);
            t.setDaemon(true);
            return t;
        });
        this.senderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-tts-send-" + id);
            t.setDaemon(true);
            return t;
        });
        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-tts-flush-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    void start() {
        if (!workersStarted.compareAndSet(false, true)) {
            return;
        }
        if (ttsConfig.isWarmupOnStart()) {
            queue.offer(FeedJob.warmup());
        }
        try {
            feedExecutor.execute(this::runFeedWorker);
            senderExecutor.execute(this::runSender);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            log.warn("App TTS workers rejected session={}: {}", webSocketSession.getId(), ex.getMessage());
            ended.set(true);
        }
    }

    void offerText(String text, boolean flush) {
        if (ended.get()) {
            return;
        }
        synchronized (speakableLock) {
            if (finishRequested.get() || ended.get()) {
                return;
            }
            for (String chunk : batcher.accept(text, false)) {
                enqueueFeed(chunk, false);
            }
            if (flush) {
                finishRequested.set(true);
                for (String chunk : batcher.accept(null, true)) {
                    enqueueFeed(chunk, false);
                }
                enqueueFeed("", true);
            }
        }
    }

    void flush() {
        offerText("", true);
    }

    @Override
    public void close() {
        ended.set(true);
        finishRequested.set(true);
        // 先软停：避免 shutdownNow 打断阻塞中的 WS send（日志里 1006 / 音频截断）
        feedExecutor.shutdown();
        flushExecutor.shutdown();
        senderExecutor.shutdown();
        awaitTerminationQuietly(senderExecutor, 800L);
        awaitTerminationQuietly(feedExecutor, 300L);
        awaitTerminationQuietly(flushExecutor, 300L);
        feedExecutor.shutdownNow();
        flushExecutor.shutdownNow();
        senderExecutor.shutdownNow();
        TtsStream stream;
        synchronized (ttsLock) {
            stream = ttsStream;
            ttsStream = null;
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ex) {
                log.debug("App TTS stream close failed session={}: {}", webSocketSession.getId(), ex.getMessage());
            }
        }
    }

    private static void awaitTerminationQuietly(ExecutorService executor, long waitMs) {
        try {
            executor.awaitTermination(Math.max(1L, waitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void enqueueFeed(String text, boolean flush) {
        queue.offer(new FeedJob(false, flush, text == null ? "" : text));
    }

    private void runFeedWorker() {
        int feedTailQuietPolls = 0;
        try {
            while (!ended.get() && !Thread.currentThread().isInterrupted()) {
                boolean didWork = false;
                try {
                    feedBusy = true;

                    if (!ended.get() && pullTtsIntoOutbound() > 0) {
                        didWork = true;
                        feedTailQuietPolls = 0;
                    }

                    if (!finishRequested.get() && batcher.pendingChars() > 0) {
                        synchronized (speakableLock) {
                            if (!finishRequested.get() && batcher.pendingChars() > 0) {
                                for (String chunk : batcher.accept(null, false)) {
                                    enqueueFeed(chunk, false);
                                    didWork = true;
                                }
                            }
                        }
                    }

                    FeedJob job = null;
                    if (mayPollFeedJob()) {
                        job = queue.poll();
                    }
                    if (job != null) {
                        didWork = true;
                        feedTailQuietPolls = 0;
                        if (job.prepare) {
                            TtsStream stream = currentStream();
                            if (stream != null) {
                                stream.prepare();
                                if (!stream.hasFailed()) {
                                    // 预热即建立连接并启动服务端空闲计时，须刷新保活时钟
                                    lastFeedNanos = System.nanoTime();
                                }
                            }
                        } else if (job.flush) {
                            drainLeftoverFeedJobs();
                            submitBlockingFlush(job.text);
                        } else {
                            processFeed(job.text, false);
                        }
                    } else if (!finishRequested.get() && feedKeepaliveDue()) {
                        // 真保活：无待喂文本也主动喂一小段，重置百炼 ~23s 空闲计时
                        sendFeedKeepalive();
                    }
                } catch (Exception ex) {
                    log.warn("App TTS feed worker failed session={}", webSocketSession.getId(), ex);
                    TtsStream stream = currentStream();
                    if (stream != null && stream.hasFailed()) {
                        signalTtsErrorOnce(stream.lastError() == null ? "tts_failed" : stream.lastError());
                    }
                } finally {
                    feedBusy = false;
                }

                if (finishRequested.get() && flushProcessed.get() && queue.isEmpty()) {
                    if (ended.get()) {
                        break;
                    }
                    if (pullTtsIntoOutbound() > 0) {
                        feedTailQuietPolls = 0;
                        continue;
                    }
                    TtsStream stream = currentStream();
                    if (stream != null && stream.pendingPcmBytes() > 0) {
                        feedTailQuietPolls = 0;
                    } else if (stream != null && !stream.hasFailed() && !stream.isUpstreamFinished()) {
                        // 上游还在合成：包间隙可能远超 200ms，必须按停滞阈值等，不能短 quiet 退出
                        feedTailQuietPolls = 0;
                        if (ttsPcmStalledFor(finishTailStallMs())) {
                            log.warn("App TTS upstream unfinished but PCM stalled session={} stallMs={}",
                                    webSocketSession.getId(), finishTailStallMs());
                            break;
                        }
                    } else if (++feedTailQuietPolls >= 4) {
                        break;
                    }
                }
                if (didWork) {
                    continue;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(50L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            feedLoopExited.set(true);
            feedBusy = false;
        }
    }

    private void runSender() {
        try {
            while (!ended.get() && !Thread.currentThread().isInterrupted()) {
                boolean didWork = false;
                try {
                    senderBusy = true;
                    if (pullTtsIntoOutbound() > 0) {
                        didWork = true;
                    }
                    while (sendOneOutboundFrame()) {
                        didWork = true;
                    }
                    if (!outboundIsEmpty() && !ended.get()) {
                        // 仍有帧但发不出去（WS 暂断）— 退避，避免 tight loop
                        try {
                            TimeUnit.MILLISECONDS.sleep(100L);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception ex) {
                    log.warn("App TTS sender failed session={}", webSocketSession.getId(), ex);
                    messageSender.sendError(webSocketSession, "TTS_SEND_FAILED", ex.getMessage());
                    ended.set(true);
                    break;
                } finally {
                    senderBusy = false;
                }

                if (shouldSenderExit()) {
                    signalTtsEndIfNeeded();
                    break;
                }
                if (didWork) {
                    continue;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(10L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            senderBusy = false;
            signalTtsEndIfNeeded();
        }
    }

    /** 须等喂字收尾完成后再发 tts_end，避免 flush 线程仍在合成时提前结束。 */
    private boolean shouldSenderExit() {
        if (ended.get()) {
            return true;
        }
        return finishRequested.get()
                && flushProcessed.get()
                && feedLoopExited.get()
                && queue.isEmpty()
                && outboundIsEmpty()
                && !feedBusy;
    }

    private void signalTtsEndIfNeeded() {
        if (!endSignaled.compareAndSet(false, true)) {
            return;
        }
        if (!begun.get()) {
            return;
        }
        try {
            messageSender.sendJson(webSocketSession, Map.of("type", "tts_end"));
        } catch (IOException ex) {
            log.debug("App TTS end signal failed session={}: {}", webSocketSession.getId(), ex.getMessage());
        }
        begun.set(false);
    }

    private void processFeed(String text, boolean flush) {
        if (ended.get()) {
            return;
        }
        pullTtsIntoOutbound();
        if (ended.get()) {
            return;
        }
        String chunk = text == null ? "" : text;
        if (!prepareTtsForFeed(chunk, flush)) {
            // 关闭竞态下 stream 被清空时不要误报 TTS_FAILED
            if (!ended.get() && ttsError != null && !ttsError.isBlank()) {
                signalTtsErrorOnce(ttsError);
            }
            return;
        }
        TtsStream stream = currentStream();
        if (stream == null) {
            if (ended.get()) {
                return;
            }
            ttsError = blankToFailed(ttsError);
            signalTtsErrorOnce(ttsError);
            return;
        }
        List<byte[]> frames = stream.feed(chunk, flush);
        if (stream.hasFailed()) {
            String error = stream.lastError();
            boolean hasText = !chunk.isBlank();
            if (hasText && isRecoverableTtsTimeout(stream, error) && trySilentRebuildTts(error)) {
                stream = currentStream();
                if (stream == null) {
                    ttsError = blankToFailed(ttsError);
                    signalTtsErrorOnce(ttsError);
                    return;
                }
                frames = stream.feed(chunk, flush);
            }
        }
        stream = currentStream();
        if (stream != null && !stream.hasFailed()) {
            lastFeedNanos = System.nanoTime();
        }
        if (stream == null || stream.hasFailed()) {
            String error = stream == null ? ttsError : stream.lastError();
            if (flush && chunk.isBlank() && framesSentCounter.get() > 0
                    && isFinishTimeoutOrRecoverableIdle(stream, error)) {
                List<byte[]> leftover = stream == null ? List.of() : stream.drainReadyFramesFully();
                offerOutbound(leftover, false);
                log.warn("App TTS finish timeout/idle, complete partial session={} frames={} leftover={} error={}",
                        webSocketSession.getId(), framesSentCounter.get(),
                        leftover == null ? 0 : leftover.size(), error);
                ttsError = null;
                return;
            }
            ttsError = blankToFailed(error);
            if (frames != null && !frames.isEmpty()) {
                offerOutbound(frames, false);
            }
            signalTtsErrorOnce(ttsError);
            return;
        }
        ttsError = null;
        if (ttsRebuildAttempts > 0 && !chunk.isBlank()) {
            // 真实文本喂字成功 = 会话已恢复，重置静默重建额度
            ttsRebuildAttempts = 0;
        }
        offerOutbound(frames, !flush);
    }

    /**
     * 喂字前确保会话可用：可恢复超时时静默重建；空 flush 遇超时且已有音频则跳过收尾。
     */
    private boolean prepareTtsForFeed(String chunk, boolean flush) {
        TtsStream stream = currentStream();
        if (stream == null) {
            ttsError = blankToFailed(ttsError);
            return false;
        }
        if (!stream.hasFailed()) {
            return true;
        }
        String error = stream.lastError();
        boolean hasText = chunk != null && !chunk.isBlank();
        if (hasText && isRecoverableTtsTimeout(stream, error) && trySilentRebuildTts(error)) {
            stream = currentStream();
            return stream != null && !stream.hasFailed();
        }
        if (flush && !hasText && isFinishTimeoutOrRecoverableIdle(stream, error)) {
            if (framesSentCounter.get() > 0) {
                List<byte[]> leftover = stream.drainReadyFramesFully();
                offerOutbound(leftover, false);
                log.warn("App TTS finish on already-timed-out session, complete partial session={} frames={} error={}",
                        webSocketSession.getId(), framesSentCounter.get(), error);
                ttsError = null;
            } else {
                ttsError = blankToFailed(error);
            }
            return false;
        }
        ttsError = blankToFailed(error);
        return false;
    }

    private void signalTtsErrorOnce(String error) {
        if (ended.get()) {
            return;
        }
        if (!errorSignaled.compareAndSet(false, true)) {
            return;
        }
        messageSender.sendError(webSocketSession, "TTS_FAILED",
                error == null || error.isBlank() ? "tts_failed" : error);
    }

    private boolean trySilentRebuildTts(String error) {
        if (!ttsConfig.isTimeoutSilentRebuild()) {
            return false;
        }
        int maxAttempts = Math.max(1, ttsConfig.getRebuildMaxAttempts());
        if (ttsRebuildAttempts >= maxAttempts) {
            log.warn("App TTS silent rebuild exhausted attempts={}/{} session={}",
                    ttsRebuildAttempts, maxAttempts, webSocketSession.getId());
            return false;
        }
        if (ended.get() || webSocketSession == null || !webSocketSession.isOpen()) {
            return false;
        }
        ttsRebuildAttempts++;
        log.info("App TTS idle timeout, silent rebuild session={} attempt={}/{} error={}",
                webSocketSession.getId(), ttsRebuildAttempts, maxAttempts, error);

        TtsStream old;
        synchronized (ttsLock) {
            old = ttsStream;
            ttsStream = null;
        }
        if (old != null) {
            try {
                List<byte[]> rest = old.drainReadyFramesFully();
                if (rest != null && !rest.isEmpty()) {
                    offerOutbound(rest, false);
                    log.info("App TTS rebuild salvaged frames={} session={}", rest.size(), webSocketSession.getId());
                }
            } catch (Exception ex) {
                log.debug("App TTS rebuild drain old stream failed session={}", webSocketSession.getId(), ex);
            }
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }

        try {
            TtsStream neu = ttsService.openStream(audioParams);
            neu.prepare();
            if (neu.hasFailed()) {
                String prepareError = neu.lastError();
                ttsError = blankToFailed(prepareError);
                log.warn("App TTS silent rebuild prepare failed session={} error={}",
                        webSocketSession.getId(), ttsError);
                try {
                    neu.close();
                } catch (Exception ignored) {
                }
                return false;
            }
            if (ended.get() || !webSocketSession.isOpen()) {
                try {
                    neu.close();
                } catch (Exception ignored) {
                }
                return false;
            }
            synchronized (ttsLock) {
                ttsStream = neu;
            }
            ttsError = null;
            // 重建即新连接：刷新保活时钟，避免新会话立即再空闲断连
            lastFeedNanos = System.nanoTime();
            log.info("App TTS silent rebuild ok session={} attempt={}",
                    webSocketSession.getId(), ttsRebuildAttempts);
            return true;
        } catch (Exception ex) {
            log.warn("App TTS silent rebuild failed session={}", webSocketSession.getId(), ex);
            ttsError = "tts_failed";
            return false;
        }
    }

    /**
     * 优先按连接状态判定（服务端在合成结束前断开即可恢复），
     * 避免依赖 provider 原始错误消息的关键字匹配。
     */
    private static boolean isRecoverableTtsTimeout(TtsStream stream, String error) {
        if (stream != null && stream.isIdleDisconnected()) {
            return true;
        }
        return isRecoverableTtsTimeout(error);
    }

    private static boolean isRecoverableTtsTimeout(String error) {
        if (error == null || error.isBlank()) {
            return false;
        }
        String trimmed = error.trim();
        // finish 超时不是「空闲断连」，不应为继续喂字去换会话
        if ("tts_finish_timeout".equals(trimmed) || "tts_closed".equals(trimmed)) {
            return false;
        }
        if ("tts_connection_closed".equals(trimmed) || "tts_timeout".equals(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase();
        return lower.contains("request timeout")
                || lower.contains("timed out")
                || (lower.contains("timeout") && !lower.contains("finish"));
    }

    private static boolean isFinishTimeoutOrRecoverableIdle(TtsStream stream, String error) {
        if (stream != null && stream.isIdleDisconnected()) {
            return true;
        }
        return isFinishTimeoutOrRecoverableIdle(error);
    }

    private static boolean isFinishTimeoutOrRecoverableIdle(String error) {
        if (error == null || error.isBlank()) {
            return false;
        }
        String trimmed = error.trim();
        if ("tts_finish_timeout".equals(trimmed)) {
            return true;
        }
        return isRecoverableTtsTimeout(trimmed);
    }

    private static String blankToFailed(String error) {
        return error == null || error.isBlank() ? "tts_failed" : error;
    }

    private TtsStream currentStream() {
        synchronized (ttsLock) {
            return ttsStream;
        }
    }

    private void submitBlockingFlush(String text) {
        if (!flushSubmitted.compareAndSet(false, true)) {
            return;
        }
        try {
            flushExecutor.execute(() -> {
                try {
                    processFeed(text == null ? "" : text, true);
                    TtsStream stream = currentStream();
                    if (stream != null && !stream.hasFailed()) {
                        List<byte[]> leftover = stream.drainReadyFramesFully();
                        offerOutbound(leftover, false);
                    }
                } catch (Exception ex) {
                    log.warn("App TTS flush failed session={}", webSocketSession.getId(), ex);
                } finally {
                    flushProcessed.set(true);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // close()/start 覆盖时线程池已终止
            flushProcessed.set(true);
            log.debug("App TTS flush rejected (session closing) session={}: {}",
                    webSocketSession.getId(), ex.getMessage());
        }
    }

    private void drainLeftoverFeedJobs() {
        FeedJob leftover;
        while ((leftover = queue.poll()) != null) {
            if (leftover.prepare || leftover.flush) {
                continue;
            }
            processFeed(leftover.text, false);
        }
    }

    private boolean mayPollFeedJob() {
        if (finishRequested.get() || feedKeepaliveDue()) {
            return true;
        }
        if (ttsPcmBackpressured()) {
            return false;
        }
        return outboundBufferedMs() < highWatermarkMs();
    }

    private boolean feedKeepaliveDue() {
        int maxIdleMs = ttsConfig.getFeedMaxIdleMs();
        if (maxIdleMs <= 0 || lastFeedNanos <= 0L) {
            return false;
        }
        long idleMs = (System.nanoTime() - lastFeedNanos) / 1_000_000L;
        return idleMs >= maxIdleMs;
    }

    /**
     * 真保活：空闲且无待喂文本时向 provider 发一小段文本，重置其空闲计时。
     * 保活产生的音频（短停顿）无需下发，直接丢弃即可。
     */
    private void sendFeedKeepalive() {
        String keepaliveText = ttsConfig.getFeedKeepaliveText();
        if (keepaliveText == null || keepaliveText.isBlank()) {
            return;
        }
        TtsStream stream = currentStream();
        if (stream == null || stream.hasFailed() || stream.isUpstreamFinished()) {
            return;
        }
        try {
            stream.feed(keepaliveText, false);
            if (!stream.hasFailed()) {
                lastFeedNanos = System.nanoTime();
            }
        } catch (Exception ex) {
            // 保活失败不报错：留给后续真实喂字走静默重建
            log.debug("App TTS keepalive failed session={}: {}", webSocketSession.getId(), ex.getMessage());
        }
    }

    private boolean ttsPcmBackpressured() {
        TtsStream stream = currentStream();
        if (stream == null || stream.hasFailed()) {
            return false;
        }
        int ms = Math.max(highWatermarkMs(), ttsConfig.getPendingPcmMaxBufferedMs());
        return stream.pendingPcmBytes() >= AbstractPcmTtsStream.maxPendingBytesFor(audioParams, ms);
    }

    private int highWatermarkMs() {
        int frameMs = Math.max(1, audioParams.frameDurationMs());
        return Math.max(frameMs, ttsConfig.getDownlinkMaxBufferedMs());
    }

    private int maxOutboundFrames() {
        int frameMs = Math.max(1, audioParams.frameDurationMs());
        return Math.max(1, (highWatermarkMs() + frameMs - 1) / frameMs);
    }

    private int outboundRoomFrames() {
        synchronized (outboundLock) {
            return Math.max(0, maxOutboundFrames() - outboundFrames.size());
        }
    }

    private int outboundBufferedMs() {
        int frameMs = Math.max(1, audioParams.frameDurationMs());
        synchronized (outboundLock) {
            return outboundFrames.size() * frameMs;
        }
    }

    private boolean outboundIsEmpty() {
        synchronized (outboundLock) {
            return outboundFrames.isEmpty();
        }
    }

    private int pullTtsIntoOutbound() {
        TtsStream stream = currentStream();
        if (stream == null) {
            return 0;
        }
        if (stream.hasFailed() && stream.pendingPcmBytes() <= 0) {
            return 0;
        }
        int room = outboundRoomFrames();
        if (room <= 0) {
            return 0;
        }
        List<byte[]> frames = stream.drainReadyFrames(room);
        if (frames == null || frames.isEmpty()) {
            return 0;
        }
        int accepted = 0;
        synchronized (outboundLock) {
            for (byte[] frame : frames) {
                if (frame != null && frame.length > 0) {
                    outboundFrames.addLast(frame);
                    accepted++;
                }
            }
        }
        return accepted;
    }

    private void offerOutbound(List<byte[]> frames, boolean enforceWatermark) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        ArrayList<byte[]> rejected = null;
        synchronized (outboundLock) {
            if (!enforceWatermark) {
                for (byte[] frame : frames) {
                    if (frame != null && frame.length > 0) {
                        outboundFrames.addLast(frame);
                    }
                }
            } else {
                for (byte[] frame : frames) {
                    if (frame == null || frame.length == 0) {
                        continue;
                    }
                    if (outboundFrames.size() < maxOutboundFrames()) {
                        outboundFrames.addLast(frame);
                    } else {
                        if (rejected == null) {
                            rejected = new ArrayList<>();
                        }
                        rejected.add(frame);
                    }
                }
            }
        }
        TtsStream stream = currentStream();
        if (rejected != null && !rejected.isEmpty() && stream != null && !stream.hasFailed()) {
            stream.returnReadyFrames(rejected);
        } else if (rejected != null && !rejected.isEmpty()) {
            synchronized (outboundLock) {
                for (byte[] frame : rejected) {
                    outboundFrames.addLast(frame);
                }
            }
        }
    }

    private boolean sendOneOutboundFrame() throws IOException {
        byte[] payload;
        synchronized (outboundLock) {
            payload = outboundFrames.pollFirst();
        }
        if (payload == null) {
            return false;
        }
        if (ended.get() || webSocketSession == null || !webSocketSession.isOpen()) {
            synchronized (outboundLock) {
                outboundFrames.addFirst(payload);
            }
            return false;
        }
        paceBeforeSend();
        if (ended.get() || !webSocketSession.isOpen()) {
            synchronized (outboundLock) {
                outboundFrames.addFirst(payload);
            }
            return false;
        }
        if (begun.compareAndSet(false, true)) {
            messageSender.sendJson(webSocketSession, Map.of("type", "tts_begin"));
        }
        messageSender.sendBinary(webSocketSession, payload);
        lastSendNanos = System.nanoTime();
        pacedContentFrames++;
        framesSentCounter.incrementAndGet();
        return true;
    }

    private void paceBeforeSend() {
        if (!ttsConfig.isDownlinkPaceEnabled()) {
            return;
        }
        int frameMs = audioParams.frameDurationMs();
        if (frameMs <= 0) {
            return;
        }
        int prebufferMs = Math.max(0, ttsConfig.getDownlinkPrebufferMs());
        int prebufferFrames = (prebufferMs + frameMs - 1) / frameMs;
        if (pacedContentFrames < prebufferFrames) {
            return;
        }
        if (lastSendNanos <= 0L) {
            return;
        }
        sleepUntil(lastSendNanos + frameMs * 1_000_000L);
    }

    private void sleepUntil(long earliestNanos) {
        long sleepNs = earliestNanos - System.nanoTime();
        if (sleepNs <= 0L) {
            return;
        }
        try {
            long deadline = System.nanoTime() + sleepNs;
            while (!ended.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, 20_000_000L));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean ttsPcmStalledFor(long stallMs) {
        TtsStream stream = currentStream();
        if (stream == null || stallMs <= 0L) {
            return true;
        }
        long last = stream.lastPcmNanos();
        // 从未收到 PCM：视为已停滞，避免 flush 后空等死循环
        if (last <= 0L) {
            return true;
        }
        return (System.nanoTime() - last) / 1_000_000L >= stallMs;
    }

    private long finishTailStallMs() {
        int dash = ttsConfig.getDashscope().getSessionTimeoutMs();
        int volc = ttsConfig.getVolc().getSessionTimeoutMs();
        return Math.max(5_000L, Math.max(dash, volc));
    }

    private record FeedJob(boolean prepare, boolean flush, String text) {
        /** 勿命名为 prepare()：会与 boolean 组件访问器冲突。 */
        static FeedJob warmup() {
            return new FeedJob(true, false, "");
        }
    }
}
