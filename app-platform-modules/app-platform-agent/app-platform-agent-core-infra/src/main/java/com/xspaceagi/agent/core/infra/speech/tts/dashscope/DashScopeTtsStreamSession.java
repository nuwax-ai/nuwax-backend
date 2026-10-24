package com.xspaceagi.agent.core.infra.speech.tts.dashscope;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeCredentials;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeEndpoint;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeEvent;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeProtocol;
import com.xspaceagi.agent.core.infra.speech.vendor.dashscope.DashScopeWebSocketTransport;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.tts.AbstractPcmTtsStream;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class DashScopeTtsStreamSession extends AbstractPcmTtsStream {

    private final SpeechProperties.DashScopeTts dashscope;
    private final HttpClient httpClient;
    private final Object lock = new Object();
    private final String taskId = UUID.randomUUID().toString();

    private DashScopeWebSocketTransport transport;
    private boolean runTaskSent;
    private boolean taskStarted;
    private boolean taskFinished;
    private boolean finishTaskSent;
    private boolean awaitingBinary;
    private boolean closed;
    private boolean failed;
    private String serverError;
    /** 服务端在 task-finished 前断开连接（~23s 空闲断连等）。 */
    private boolean idleDisconnected;

    public DashScopeTtsStreamSession(SpeechProperties speechProperties, AudioParams audioParams, HttpClient httpClient) {
        super(audioParams);
        this.dashscope = speechProperties.getTts().getDashscope();
        this.httpClient = httpClient;
    }

    @Override
    public void prepare() {
        try {
            ensureTaskStarted();
        } catch (Exception ex) {
            synchronized (lock) {
                failed = true;
                if (serverError == null || serverError.isBlank()) {
                    serverError = ex.getMessage() == null ? "tts_failed" : ex.getMessage();
                }
            }
            if (isBenignInterrupt(serverError)) {
                log.info("DashScope TTS prepare cancelled task={} connectId={} error={}",
                        taskId, connectId(), serverError);
            } else {
                log.warn("DashScope TTS prepare failed task={} connectId={} error={}",
                        taskId, connectId(), serverError, ex);
            }
        }
    }

    @Override
    public List<byte[]> feed(String textChunk, boolean flush) {
        synchronized (lock) {
            if (closed || failed) {
                return List.of();
            }
        }
        try {
            ensureTaskStarted();
            if (textChunk != null && !textChunk.isBlank()) {
                sendContinueTask(textChunk);
                int waitMs = flush ? dashscope.getChunkWaitMs() : dashscope.getNonFlushChunkWaitMs();
                waitForPendingAudio(waitMs);
            }
            if (flush) {
                finishTask();
            }
            boolean fullyDone;
            synchronized (lock) {
                if (failed) {
                    return List.of();
                }
                fullyDone = taskFinished;
            }
            // 上游未结束时不要 pad 半帧，否则后续 PCM 会接在静音垫齐之后产生杂音/缝隙
            return drainPcmFrames(flush && fullyDone);
        } catch (Exception ex) {
            synchronized (lock) {
                failed = true;
                if (serverError == null || serverError.isBlank()) {
                    serverError = ex.getMessage() == null ? "tts_failed" : ex.getMessage();
                }
            }
            if (isBenignInterrupt(serverError)) {
                log.info("DashScope TTS feed cancelled task={} connectId={} error={}",
                        taskId, connectId(), serverError);
            } else {
                log.error("DashScope TTS feed failed task={} connectId={} error={}",
                        taskId, connectId(), serverError, ex);
            }
            return List.of();
        }
    }

    @Override
    public String lastError() {
        synchronized (lock) {
            return serverError;
        }
    }

    @Override
    public boolean isIdleDisconnected() {
        synchronized (lock) {
            return idleDisconnected;
        }
    }

    @Override
    public boolean hasFailed() {
        synchronized (lock) {
            return failed;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (!taskFinished && (serverError == null || serverError.isBlank())) {
                serverError = "tts_closed";
            }
            lock.notifyAll();
        }
        markPcmClosed();
        try {
            if (shouldSendFinishTask()) {
                sendFinishTaskQuietly();
            }
        } catch (Exception ex) {
            log.debug("DashScope TTS close cleanup failed task={}", taskId, ex);
        } finally {
            closeTransport();
        }
    }

    private boolean shouldSendFinishTask() {
        synchronized (lock) {
            return transport != null && taskStarted && !finishTaskSent;
        }
    }

    private void ensureTaskStarted() throws Exception {
        synchronized (lock) {
            if (taskStarted) {
                return;
            }
            if (closed || failed) {
                throw new IllegalStateException(serverError == null ? "tts_failed" : serverError);
            }
        }
        initTransportIfNeeded();
        DashScopeWebSocketTransport active = transport;
        active.ensureOpen();

        boolean shouldSendRunTask = false;
        String runTask = null;
        synchronized (lock) {
            if (taskStarted) {
                return;
            }
            if (!runTaskSent) {
                runTaskSent = true;
                shouldSendRunTask = true;
                int sampleRate = resolveSampleRate();
                runTask = DashScopeProtocol.buildRunTaskTts(taskId, dashscope.getModel(), dashscope.getVoice(),
                        dashscope.getFormat(), sampleRate,
                        dashscope.getVolume(), dashscope.getRate(), dashscope.getPitch());
            }
        }
        if (shouldSendRunTask) {
            active.sendText(runTask);
        }
        awaitTaskStarted();
        if (shouldSendRunTask) {
            log.info("DashScope TTS task started task={} connectId={} endpoint={}",
                    taskId, connectId(), endpoint());
        }
    }

    private void initTransportIfNeeded() {
        synchronized (lock) {
            if (transport != null) {
                return;
            }
            DashScopeCredentials.requireApiKey(dashscope.getApiKey());
            DashScopeCredentials.requireWorkspaceId(dashscope.getWorkspaceId());
            transport = new DashScopeWebSocketTransport(httpClient, endpoint(), dashscope.getApiKey(),
                    dashscope.getWorkspaceId(), dashscope.getConnectTimeoutMs(), dashscope.getSendTimeoutMs(),
                    this::onTransportText, this::onTransportBinary, this::onTransportClosed);
        }
    }

    private void sendContinueTask(String text) throws Exception {
        DashScopeWebSocketTransport active = transport;
        if (active == null) {
            throw new IllegalStateException("DashScope TTS transport not ready");
        }
        synchronized (lock) {
            awaitingBinary = true;
        }
        active.sendText(DashScopeProtocol.buildContinueTask(taskId, text));
    }

    private void finishTask() throws Exception {
        synchronized (lock) {
            if (taskFinished || finishTaskSent || !taskStarted) {
                return;
            }
            finishTaskSent = true;
        }
        sendFinishTaskQuietly();
        awaitTaskFinished();
        waitForPendingAudio(dashscope.getChunkWaitMs());
    }

    private void sendFinishTaskQuietly() {
        try {
            DashScopeWebSocketTransport active = transport;
            if (active != null) {
                active.sendText(DashScopeProtocol.buildFinishTask(taskId));
            }
        } catch (Exception ex) {
            log.debug("DashScope TTS finish task failed task={}", taskId, ex);
        }
    }

    private void onTransportText(String json) {
        synchronized (lock) {
            try {
                DashScopeEvent event = DashScopeProtocol.parseEvent(json);
                if (!event.matchesTaskId(taskId)) {
                    log.debug("DashScope TTS ignore event={} foreignTask={} task={}",
                            event.event(), event.taskId(), taskId);
                    return;
                }
                if (event.isTaskFailed()) {
                    failed = true;
                    serverError = event.errorMessage() == null || event.errorMessage().isBlank()
                            ? "tts_error" : event.errorMessage();
                    log.error("DashScope TTS task failed task={} connectId={} error={}",
                            taskId, connectId(), serverError);
                    lock.notifyAll();
                    return;
                }
                if (event.isTaskStarted()) {
                    taskStarted = true;
                    lock.notifyAll();
                    return;
                }
                if (event.isResultGenerated() && event.isTtsSentenceSynthesis()) {
                    awaitingBinary = true;
                    lock.notifyAll();
                    return;
                }
                if (event.isTaskFinished()) {
                    taskFinished = true;
                    awaitingBinary = false;
                    lock.notifyAll();
                }
            } catch (IOException ex) {
                failed = true;
                serverError = "tts_parse_failed";
                log.error("DashScope TTS failed to parse event task={} connectId={}", taskId, connectId(), ex);
                lock.notifyAll();
            }
        }
    }

    private void onTransportBinary(byte[] pcm) {
        boolean accept;
        synchronized (lock) {
            accept = !failed && !closed;
        }
        if (!accept) {
            return;
        }
        try {
            if (pcm != null && pcm.length > 0) {
                // appendPcm 不得阻塞 Netty 读回调
                appendPcm(pcm);
            }
            synchronized (lock) {
                if (!failed && !closed) {
                    awaitingBinary = false;
                }
                lock.notifyAll();
            }
        } catch (IOException ex) {
            synchronized (lock) {
                failed = true;
                serverError = "tts_audio_failed";
                log.error("DashScope TTS failed to buffer audio task={} connectId={}", taskId, connectId(), ex);
                lock.notifyAll();
            }
        }
    }

    private void onTransportClosed() {
        synchronized (lock) {
            if (closed) {
                lock.notifyAll();
                return;
            }
            if (!taskFinished) {
                failed = true;
                idleDisconnected = true;
                if (serverError == null) {
                    serverError = "tts_connection_closed";
                }
            }
            lock.notifyAll();
        }
    }

    private void closeTransport() {
        DashScopeWebSocketTransport active;
        synchronized (lock) {
            active = transport;
            transport = null;
        }
        if (active != null) {
            try {
                active.close();
            } catch (Exception ex) {
                log.debug("DashScope TTS transport close failed task={}", taskId, ex);
            }
        }
    }

    private void awaitTaskStarted() throws InterruptedException {
        synchronized (lock) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs());
            while (!taskStarted && !failed && !closed && System.nanoTime() < deadline) {
                long waitMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (waitMs <= 0) {
                    break;
                }
                lock.wait(waitMs);
            }
            if (closed) {
                throw new IllegalStateException(serverError == null ? "tts_closed" : serverError);
            }
            if (!taskStarted) {
                throw new IllegalStateException(serverError == null ? "tts_task_start_timeout" : serverError);
            }
        }
    }

    private void awaitTaskFinished() throws InterruptedException {
        synchronized (lock) {
            // 长文 finish 后仍可能持续合成：按「无音频进展」停滞计时，而非绝对截止
            long stallNs = TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs()));
            long lastProgressAt = System.nanoTime();
            long lastSeenPcm = lastPcmNanos();
            while (!taskFinished && !failed && !closed) {
                long pcmAt = lastPcmNanos();
                if (pcmAt > lastSeenPcm) {
                    lastSeenPcm = pcmAt;
                    lastProgressAt = System.nanoTime();
                }
                long idleNs = System.nanoTime() - lastProgressAt;
                if (idleNs >= stallNs) {
                    break;
                }
                long waitMs = TimeUnit.NANOSECONDS.toMillis(stallNs - idleNs);
                if (waitMs <= 0L) {
                    break;
                }
                lock.wait(Math.min(waitMs, 200L));
            }
            if (closed && !taskFinished) {
                throw new IllegalStateException(serverError == null ? "tts_closed" : serverError);
            }
            // 软超时：不置 failed，否则会拒收后续 binary
            if (!taskFinished && !failed && !closed) {
                log.warn("DashScope TTS finish stalled, keep receiving PCM task={} connectId={} stallMs={}",
                        taskId, connectId(), timeoutMs());
            }
        }
    }

    @Override
    public boolean isUpstreamFinished() {
        synchronized (lock) {
            return taskFinished;
        }
    }

    private void waitForPendingAudio(int waitMs) throws InterruptedException {
        if (waitMs <= 0) {
            return;
        }
        synchronized (lock) {
            if (!awaitingBinary) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
            while (awaitingBinary && !closed && !failed && System.nanoTime() < deadline) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    break;
                }
                lock.wait(remaining);
            }
            if (awaitingBinary && System.nanoTime() >= deadline) {
                awaitingBinary = false;
            }
        }
    }

    private int resolveSampleRate() {
        int downlink = audioParams().downlinkSampleRate();
        if (downlink > 0) {
            return downlink;
        }
        return dashscope.getSampleRate() > 0 ? dashscope.getSampleRate() : 16000;
    }

    private int timeoutMs() {
        return dashscope.getSessionTimeoutMs();
    }

    private String endpoint() {
        return DashScopeEndpoint.resolve(dashscope.getWorkspaceId(), dashscope.getRegion(), dashscope.getEndpoint());
    }

    private String connectId() {
        synchronized (lock) {
            return transport == null ? "-" : transport.connectId();
        }
    }

    private static boolean isBenignInterrupt(String error) {
        return "tts_closed".equals(error);
    }
}
