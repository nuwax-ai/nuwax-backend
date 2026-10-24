package com.xspaceagi.agent.core.infra.speech.asr.dashscope;

import com.xspaceagi.agent.core.infra.speech.asr.AsrListener;
import com.xspaceagi.agent.core.infra.speech.asr.AsrResult;
import com.xspaceagi.agent.core.infra.speech.asr.AsrSentence;
import com.xspaceagi.agent.core.infra.speech.asr.AsrService;
import com.xspaceagi.agent.core.infra.speech.asr.PcmFrameAccumulator;
import com.xspaceagi.agent.core.infra.speech.audio.OpusDecoder;
import com.xspaceagi.agent.core.infra.speech.audio.Pcm16Resampler;
import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeCredentials;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeEndpoint;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeEvent;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeProtocol;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeWebSocketTransport;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.protocol.NaFrame;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DashScopeAsrService implements AsrService {

    private final SpeechProperties.Asr asr;
    private final SpeechProperties.DashScopeAsr dashscope;
    private final AudioParams audioParams;
    private final PcmFrameAccumulator pcmAccumulator;
    private final HttpClient httpClient;
    private final Object stateLock = new Object();

    private final String taskId = UUID.randomUUID().toString();
    private DashScopeWebSocketTransport transport;
    private boolean runTaskSent;
    private boolean taskStarted;
    private boolean taskFinished;
    private boolean finishTaskSent;
    private boolean sentAudio;
    private boolean sendFailed;
    private boolean closed;
    /**
     * Fun-ASR 的 {@code sentence.text} 是<strong>当前句</strong>文本，不是累计全文。
     * 定稿句追加到 {@link #committedText}；未定稿句放在 {@link #partialText}。
     * 若只保留 latest 覆盖，多句会话 flush 只会得到最后一句（监控 19s 录音只剩「做些配置」即此因）。
     */
    private final StringBuilder committedText = new StringBuilder();
    private final List<AsrSentence> committedSentences = new ArrayList<>();
    private String partialText = "";
    private final java.util.Set<Integer> committedSentenceIds = new java.util.HashSet<>();
    private Integer latestDurationMs;
    private String serverError;
    /** 服务端在 task-finished 前断开连接（~23s 空闲断连等）。 */
    private boolean idleDisconnected;
    private CountDownLatch resultLatch;
    private AsrListener listener;

    public DashScopeAsrService(SpeechProperties speechProperties, OpusDecoder opusDecoder, AudioParams audioParams,
            HttpClient httpClient) {
        this.asr = speechProperties.getAsr();
        this.dashscope = asr.getDashscope();
        this.audioParams = audioParams;
        this.audioParams.requireValid();
        this.pcmAccumulator = new PcmFrameAccumulator(audioParams, opusDecoder);
        this.httpClient = httpClient;
    }

    @Override
    public void setListener(AsrListener listener) {
        synchronized (stateLock) {
            this.listener = listener;
        }
    }

    @Override
    public void prepare() {
        try {
            ensureTaskStarted();
        } catch (Exception ex) {
            markSendFailed(ex);
        }
    }

    @Override
    public void feedFrame(NaFrame frame) {
        synchronized (stateLock) {
            if (closed || sendFailed) {
                return;
            }
            pcmAccumulator.append(frame);
        }
        try {
            pumpBufferedAudio(false);
        } catch (Exception ex) {
            markSendFailed(ex);
        }
    }

    @Override
    public AsrResult flush() {
        try {
            CountDownLatch awaitLatch;
            synchronized (stateLock) {
                if (closed) {
                    return AsrResult.failure("asr_closed");
                }
                if (sendFailed) {
                    return AsrResult.failure(resolveFailure("asr_send_failed"));
                }
                if (serverError != null && !serverError.isBlank()) {
                    return AsrResult.failure(serverError);
                }
                if (pcmAccumulator.size() == 0 && !sentAudio) {
                    return AsrResult.failure("no_speech");
                }
                resultLatch = new CountDownLatch(1);
                taskFinished = false;
                serverError = null;
                awaitLatch = resultLatch;
            }
            try {
                pumpBufferedAudio(true);
                sendFinishTaskIfNeeded();
            } catch (Exception ex) {
                log.warn("DashScope ASR flush failed task={} connectId={}", taskId, connectId(), ex);
                synchronized (stateLock) {
                    return AsrResult.failure(resolveFailure(
                            ex.getMessage() == null || ex.getMessage().isBlank() ? "asr_failed" : ex.getMessage()));
                }
            }
            try {
                return awaitResult(awaitLatch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return AsrResult.failure("asr_interrupted");
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        DashScopeWebSocketTransport toClose;
        synchronized (stateLock) {
            closed = true;
            signalDone();
            toClose = transport;
            transport = null;
        }
        if (toClose != null) {
            try {
                toClose.close();
            } catch (Exception ex) {
                log.debug("DashScope ASR transport close failed task={}", taskId, ex);
            }
        }
    }

    private AsrResult awaitResult(CountDownLatch latch) throws InterruptedException {
        if (latch == null) {
            return AsrResult.failure("asr_failed");
        }
        int timeoutMs = dashscope.getResultTimeoutMs();
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            synchronized (stateLock) {
                if (closed) {
                    return AsrResult.failure("asr_closed");
                }
                String timeoutText = assembledTranscript();
                if (timeoutText != null && !timeoutText.isBlank()) {
                    return AsrResult.success(timeoutText, latestDurationMs, snapshotSentences(true));
                }
            }
            return AsrResult.failure("asr_timeout");
        }
        synchronized (stateLock) {
            if (closed) {
                return AsrResult.failure("asr_closed");
            }
            if (serverError != null && !serverError.isBlank()) {
                return AsrResult.failure(serverError);
            }
            String text = assembledTranscript();
            if (text == null || text.isBlank()) {
                return AsrResult.failure("no_speech");
            }
            return AsrResult.success(text, latestDurationMs, snapshotSentences(true));
        }
    }

    /**
     * 定稿句列表；{@code includePartial} 时把未定稿 partial 附在末尾（无精确时间戳）。
     */
    private List<AsrSentence> snapshotSentences(boolean includePartial) {
        List<AsrSentence> out = new ArrayList<>(committedSentences);
        if (includePartial) {
            String partial = partialText == null ? "" : partialText.trim();
            if (!partial.isEmpty()) {
                out.add(new AsrSentence(partial, null, null));
            }
        }
        return List.copyOf(out);
    }

    /** 已定稿句 + 当前未定稿 partial（若有）。 */
    private String assembledTranscript() {
        String committed = committedText.toString();
        String partial = partialText == null ? "" : partialText.trim();
        if (committed.isEmpty()) {
            return partial;
        }
        if (partial.isEmpty()) {
            return committed.trim();
        }
        return (committed + partial).trim();
    }

    private void ensureTaskStarted() throws Exception {
        synchronized (stateLock) {
            if (taskStarted) {
                return;
            }
            if (closed || sendFailed) {
                throw new IllegalStateException("DashScope ASR session unavailable");
            }
        }
        initTransportIfNeeded();
        DashScopeWebSocketTransport active = transport;
        if (active == null) {
            throw new IllegalStateException("DashScope ASR transport unavailable");
        }
        active.ensureOpen();

        boolean shouldSendRunTask = false;
        String runTask = null;
        synchronized (stateLock) {
            if (taskStarted) {
                return;
            }
            if (!runTaskSent) {
                runTaskSent = true;
                shouldSendRunTask = true;
                runTask = DashScopeProtocol.buildRunTaskAsr(taskId, dashscope.getModel(), dashscope.getFormat(),
                        dashscope.getSampleRate(), dashscope.resolveMaxSentenceSilenceMs(asr),
                        dashscope.getSpeechNoiseThreshold());
            }
        }
        if (shouldSendRunTask) {
            active.sendText(runTask);
        }
        awaitTaskStarted();
        if (shouldSendRunTask) {
            log.info("DashScope ASR task started task={} connectId={} endpoint={}",
                    taskId, active.connectId(), endpoint());
        }
    }

    private void initTransportIfNeeded() {
        if (transport != null) {
            return;
        }
        synchronized (stateLock) {
            if (transport != null || closed || sendFailed) {
                return;
            }
            DashScopeCredentials.requireApiKey(dashscope.getApiKey());
            DashScopeCredentials.requireWorkspaceId(dashscope.getWorkspaceId());
            transport = new DashScopeWebSocketTransport(httpClient, endpoint(), dashscope.getApiKey(),
                    dashscope.getWorkspaceId(), dashscope.getConnectTimeoutMs(), dashscope.getSendTimeoutMs(),
                    this::onTransportText, null, this::onTransportClosed);
        }
    }

    private void pumpBufferedAudio(boolean terminal) throws Exception {
        ensureTaskStarted();
        while (true) {
            byte[] chunk;
            boolean last;
            synchronized (stateLock) {
                if (sendFailed || closed) {
                    return;
                }
                int chunkSize = chunkBytes();
                if (pcmAccumulator.size() >= chunkSize) {
                    chunk = pcmAccumulator.takeBytes(chunkSize);
                    last = false;
                } else if (terminal) {
                    if (pcmAccumulator.size() > 0) {
                        chunk = pcmAccumulator.takeAll();
                        last = true;
                    } else if (sentAudio) {
                        chunk = new byte[0];
                        last = true;
                    } else {
                        return;
                    }
                } else {
                    return;
                }
            }
            chunk = resampleForDashScope(chunk);
            sendAudioChunk(chunk);
            if (last) {
                return;
            }
        }
    }

    private void sendFinishTaskIfNeeded() throws Exception {
        synchronized (stateLock) {
            if (finishTaskSent || sendFailed || closed || !taskStarted) {
                return;
            }
            finishTaskSent = true;
        }
        DashScopeWebSocketTransport active = transport;
        if (active == null) {
            throw new IllegalStateException("DashScope ASR websocket not connected");
        }
        active.sendText(DashScopeProtocol.buildFinishTask(taskId));
    }

    private void sendAudioChunk(byte[] pcm) throws Exception {
        synchronized (stateLock) {
            if (sendFailed || closed) {
                return;
            }
            sentAudio = sentAudio || pcm.length > 0;
        }
        DashScopeWebSocketTransport active = transport;
        if (active == null) {
            throw new IllegalStateException("DashScope ASR websocket not connected");
        }
        if (pcm.length > 0) {
            active.sendBinary(pcm);
        }
    }

    private void awaitTaskStarted() throws InterruptedException {
        synchronized (stateLock) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(dashscope.getConnectTimeoutMs());
            while (!taskStarted && !sendFailed && !closed && System.nanoTime() < deadline) {
                long waitMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (waitMs <= 0) {
                    break;
                }
                stateLock.wait(waitMs);
            }
            if (closed || sendFailed) {
                throw new IllegalStateException(serverError == null ? "asr_failed" : serverError);
            }
            if (!taskStarted) {
                throw new IllegalStateException("asr_task_start_timeout");
            }
        }
    }

    private void onTransportText(String json) {
        AsrListener notifyListener = null;
        AsrSentence finalSentence = null;
        String partial = null;
        String errorForListener = null;
        synchronized (stateLock) {
            try {
                DashScopeEvent event = DashScopeProtocol.parseEvent(json);
                if (!event.matchesTaskId(taskId)) {
                    log.debug("DashScope ASR ignore event={} foreignTask={} task={}",
                            event.event(), event.taskId(), taskId);
                    return;
                }
                if (event.isTaskFailed()) {
                    serverError = formatVendorError(event.errorCode(), event.errorMessage());
                    if ("no_speech".equals(serverError)) {
                        log.info("DashScope ASR no speech task={} connectId={}", taskId, connectId());
                    } else {
                        log.warn("DashScope ASR task failed task={} connectId={} error={}",
                                taskId, connectId(), serverError);
                    }
                    notifyListener = listener;
                    errorForListener = serverError;
                    signalDone();
                } else if (event.isTaskStarted()) {
                    taskStarted = true;
                    stateLock.notifyAll();
                } else if (event.isResultGenerated()) {
                    if (event.isAsrHeartbeat()) {
                        return;
                    }
                    String text = event.extractAsrText();
                    if (text == null || text.isBlank()) {
                        return;
                    }
                    Integer endTimeMs = event.extractAsrEndTimeMs();
                    if (endTimeMs != null) {
                        latestDurationMs = endTimeMs;
                    } else {
                        Integer durationMs = event.extractAsrDurationMs();
                        if (durationMs != null) {
                            latestDurationMs = durationMs;
                        }
                    }
                    Integer sentenceId = event.extractAsrSentenceId();
                    if (event.isAsrSentenceEnd()) {
                        // 同句定稿事件可能重放；按 sentence_id 去重（null 时仍追加）
                        boolean accept = sentenceId == null || committedSentenceIds.add(sentenceId);
                        if (accept) {
                            committedText.append(text);
                            finalSentence = new AsrSentence(
                                    text,
                                    event.extractAsrBeginTimeMs(),
                                    event.extractAsrEndTimeMs());
                            committedSentences.add(finalSentence);
                            notifyListener = listener;
                        }
                        partialText = "";
                    } else {
                        partialText = text;
                        partial = text;
                        notifyListener = listener;
                    }
                    log.debug("DashScope ASR assembled={} durationMs={} task={} connectId={} sentenceEnd={} sentenceId={}",
                            assembledTranscript(), latestDurationMs, taskId, connectId(),
                            event.isAsrSentenceEnd(), sentenceId);
                } else if (event.isTaskFinished()) {
                    taskFinished = true;
                    signalDone();
                }
            } catch (Exception ex) {
                log.warn("DashScope ASR failed to parse event task={} connectId={}", taskId, connectId(), ex);
                serverError = "asr_parse_failed";
                notifyListener = listener;
                errorForListener = serverError;
                signalDone();
            }
        }
        notifyAsrListener(notifyListener, finalSentence, partial, errorForListener);
    }

    private void notifyAsrListener(AsrListener listener, AsrSentence finalSentence, String partial, String error) {
        if (listener == null) {
            return;
        }
        try {
            if (error != null && !error.isBlank()) {
                listener.onError(error);
                return;
            }
            if (finalSentence != null) {
                listener.onSentenceFinal(finalSentence);
            } else if (partial != null) {
                listener.onPartial(partial);
            }
        } catch (Exception ex) {
            log.warn("DashScope ASR listener callback failed", ex);
        }
    }

    private void onTransportClosed() {
        AsrListener notify = null;
        String error = null;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            if (finishTaskSent && !taskFinished && serverError == null) {
                signalDone();
                return;
            }
            if (!taskFinished && !sendFailed) {
                sendFailed = true;
                idleDisconnected = true;
                if (serverError == null || serverError.isBlank()) {
                    serverError = "asr_connection_closed";
                }
                log.warn("DashScope ASR connection closed unexpectedly task={} connectId={} taskStarted={}",
                        taskId, connectId(), taskStarted);
                notify = listener;
                error = serverError;
                signalDone();
            }
        }
        if (notify != null && error != null) {
            try {
                notify.onError(error);
            } catch (Exception ex) {
                log.warn("DashScope ASR onError callback failed", ex);
            }
        }
    }

    @Override
    public boolean isIdleDisconnected() {
        synchronized (stateLock) {
            return idleDisconnected;
        }
    }

    private void markSendFailed(Exception ex) {
        AsrListener notify;
        String error;
        synchronized (stateLock) {
            sendFailed = true;
            if (serverError == null || serverError.isBlank()) {
                serverError = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? "asr_send_failed" : ex.getMessage();
            }
            log.warn("DashScope ASR send failed task={} connectId={}", taskId, connectId(), ex);
            notify = listener;
            error = serverError;
            stateLock.notifyAll();
            signalDone();
        }
        if (notify != null) {
            try {
                notify.onError(error);
            } catch (Exception callbackEx) {
                log.warn("DashScope ASR onError callback failed", callbackEx);
            }
        }
    }

    private void signalDone() {
        CountDownLatch latch = resultLatch;
        if (latch != null) {
            latch.countDown();
        }
        stateLock.notifyAll();
    }

    /** Prefer vendor/detail error when present; otherwise fall back to a stable code. */
    private String resolveFailure(String fallback) {
        return serverError == null || serverError.isBlank() ? fallback : serverError;
    }

    private static String formatVendorError(String errorCode, String errorMessage) {
        String code = errorCode == null || errorCode.isBlank() ? null : errorCode.trim();
        String msg = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
        if (isEmptyAudio(code, msg)) {
            return "no_speech";
        }
        if (isRequestTimeout(code, msg)) {
            return "asr_timeout";
        }
        if (code != null && msg != null) {
            return truncate("asr_error:" + code + ":" + msg);
        }
        if (code != null) {
            return truncate("asr_error:" + code);
        }
        if (msg != null) {
            return truncate("asr_error:" + msg);
        }
        return "asr_error:unknown";
    }

    private static boolean isEmptyAudio(String errorCode, String errorMessage) {
        return isEmptyAudioToken(errorCode) || isEmptyAudioToken(errorMessage);
    }

    private static boolean isRequestTimeout(String errorCode, String errorMessage) {
        return looksLikeRequestTimeout(errorCode) || looksLikeRequestTimeout(errorMessage);
    }

    private static boolean isEmptyAudioToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = token.trim();
        return "EmptyAudio".equalsIgnoreCase(t)
                || "EMPTY_AUDIO".equalsIgnoreCase(t)
                || t.toLowerCase().contains("emptyaudio")
                || t.toLowerCase().contains("empty audio");
    }

    private static boolean looksLikeRequestTimeout(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = token.trim().toLowerCase();
        return t.contains("request timeout") || t.contains("timed out")
                || "timeout".equals(t) || t.startsWith("timeout ")
                || t.contains("idle timeout");
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private int chunkBytes() {
        int chunkMs = Math.max(100, dashscope.getChunkMs());
        return dashscope.getSampleRate() * chunkMs / 1000 * 2;
    }

    private byte[] resampleForDashScope(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return pcm == null ? new byte[0] : pcm;
        }
        int uplinkRate = audioParams.uplinkSampleRate();
        int targetRate = dashscope.getSampleRate();
        if (uplinkRate == targetRate) {
            return pcm;
        }
        return Pcm16Resampler.resample(pcm, uplinkRate, targetRate);
    }

    private String endpoint() {
        return DashScopeEndpoint.resolve(dashscope.getWorkspaceId(), dashscope.getRegion(), dashscope.getEndpoint());
    }

    private String connectId() {
        return transport == null ? "-" : transport.connectId();
    }
}
