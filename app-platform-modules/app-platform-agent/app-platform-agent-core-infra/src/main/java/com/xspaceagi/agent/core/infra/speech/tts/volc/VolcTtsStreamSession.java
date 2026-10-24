package com.xspaceagi.agent.core.infra.speech.tts.volc;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.tts.AbstractPcmTtsStream;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class VolcTtsStreamSession extends AbstractPcmTtsStream {

    private final SpeechProperties speechProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Object lock = new Object();
    private final String sessionId = UUID.randomUUID().toString();

    private VolcTtsWebSocketTransport transport;
    private boolean connectionStarted;
    private boolean sessionStarted;
    private boolean sessionFinished;
    private boolean closed;
    private boolean failed;
    private String serverError;
    private boolean awaitingAudio;

    public VolcTtsStreamSession(SpeechProperties speechProperties, ObjectMapper objectMapper, AudioParams audioParams,
            HttpClient httpClient) {
        super(audioParams);
        this.speechProperties = speechProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public void prepare() {
        try {
            ensureSessionStarted();
        } catch (Exception ex) {
            synchronized (lock) {
                failed = true;
                if (serverError == null || serverError.isBlank()) {
                    serverError = ex.getMessage() == null ? "tts_failed" : ex.getMessage();
                }
            }
            if (isBenignInterrupt(serverError)) {
                log.info("Volc TTS prepare cancelled session={} connectId={} error={}",
                        sessionId, connectId(), serverError);
            } else {
                log.warn("Volc TTS prepare failed session={} connectId={} error={}",
                        sessionId, connectId(), serverError, ex);
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
            ensureSessionStarted();
            if (textChunk != null && !textChunk.isBlank()) {
                sendTaskRequest(textChunk);
                synchronized (lock) {
                    awaitingAudio = true;
                }
            }
            int waitMs = flush
                    ? speechProperties.getTts().getVolc().getChunkWaitMs()
                    : speechProperties.getTts().getVolc().getNonFlushChunkWaitMs();
            waitForAudio(waitMs);
            if (flush) {
                finishSession();
            }
            boolean fullyDone;
            synchronized (lock) {
                if (failed) {
                    return List.of();
                }
                fullyDone = sessionFinished;
            }
            return drainPcmFrames(flush && fullyDone);
        } catch (Exception ex) {
            synchronized (lock) {
                failed = true;
                if (serverError == null || serverError.isBlank()) {
                    serverError = ex.getMessage() == null ? "tts_failed" : ex.getMessage();
                }
            }
            if (isBenignInterrupt(serverError)) {
                log.info("Volc TTS feed cancelled session={} connectId={} logId={} error={}",
                        sessionId, connectId(), logId(), serverError);
            } else {
                log.error("Volc TTS feed failed session={} connectId={} logId={} error={}",
                        sessionId, connectId(), logId(), serverError, ex);
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
            if (!sessionFinished && (serverError == null || serverError.isBlank())) {
                serverError = "tts_closed";
            }
            lock.notifyAll();
        }
        markPcmClosed();
        try {
            if (shouldSendFinishSession()) {
                sendFinishSessionQuietly();
            }
            if (shouldSendFinishConnection()) {
                sendFinishConnectionQuietly();
            }
        } catch (Exception ex) {
            log.debug("Volc TTS close cleanup failed session={}", sessionId, ex);
        } finally {
            closeTransport();
        }
    }

    private boolean shouldSendFinishSession() {
        synchronized (lock) {
            return transport != null && connectionStarted && !sessionFinished && sessionStarted;
        }
    }

    private boolean shouldSendFinishConnection() {
        synchronized (lock) {
            return transport != null && connectionStarted;
        }
    }

    private void ensureSessionStarted() throws Exception {
        synchronized (lock) {
            if (sessionStarted) {
                return;
            }
            if (closed || failed) {
                throw new IllegalStateException(serverError == null ? "tts_failed" : serverError);
            }
        }
        ensureConnectionStarted();
        sendStartSession();
        awaitSessionStarted();
    }

    private void ensureConnectionStarted() throws Exception {
        synchronized (lock) {
            if (connectionStarted) {
                return;
            }
            VolcTtsCredentials.requireConfigured(speechProperties.getTts().getVolc());
            transport = new VolcTtsWebSocketTransport(httpClient, speechProperties.getTts().getVolc(),
                    this::onTransportMessage, this::onTransportClosed);
        }
        transport.ensureOpen();
        sendBinary(VolcTtsProtocol.buildClientEvent(VolcTtsProtocol.EVENT_START_CONNECTION, null,
                "{}".getBytes(StandardCharsets.UTF_8)));
        awaitConnectionStarted();
        log.info("Volc TTS connected session={} connectId={} logId={} endpoint={}",
                sessionId, connectId(), logId(), speechProperties.getTts().getVolc().getEndpoint());
    }

    private void sendStartSession() throws Exception {
        SpeechProperties.VolcTts volc = speechProperties.getTts().getVolc();
        int sampleRate = resolveSampleRate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", Map.of("uid", sessionId));
        payload.put("namespace", "BidirectionalTTS");
        payload.put("req_params", Map.of(
                "speaker", volc.getSpeaker(),
                "model", volc.getModel(),
                "audio_params", Map.of(
                        "format", "pcm",
                        "sample_rate", sampleRate)));
        byte[] json = objectMapper.writeValueAsBytes(payload);
        sendBinary(VolcTtsProtocol.buildClientEvent(VolcTtsProtocol.EVENT_START_SESSION, sessionId, json));
    }

    private void sendTaskRequest(String text) throws Exception {
        Map<String, Object> payload = Map.of("req_params", Map.of("text", text));
        byte[] json = objectMapper.writeValueAsBytes(payload);
        sendBinary(VolcTtsProtocol.buildClientEvent(VolcTtsProtocol.EVENT_TASK_REQUEST, sessionId, json));
    }

    private void finishSession() throws Exception {
        synchronized (lock) {
            if (sessionFinished || !sessionStarted) {
                return;
            }
        }
        sendFinishSession();
        awaitSessionFinished();
        waitForAudio(speechProperties.getTts().getVolc().getChunkWaitMs());
    }

    private void sendFinishSession() throws Exception {
        sendBinary(VolcTtsProtocol.buildClientEvent(VolcTtsProtocol.EVENT_FINISH_SESSION, sessionId,
                "{}".getBytes(StandardCharsets.UTF_8)));
    }

    private void sendFinishSessionQuietly() {
        try {
            sendFinishSession();
        } catch (Exception ex) {
            log.debug("Volc TTS finish session failed session={}", sessionId, ex);
        }
    }

    private void sendFinishConnectionQuietly() {
        try {
            sendBinary(VolcTtsProtocol.buildClientEvent(VolcTtsProtocol.EVENT_FINISH_CONNECTION, null,
                    "{}".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            log.debug("Volc TTS finish connection failed session={}", sessionId, ex);
        }
    }

    private void sendBinary(byte[] frame) throws Exception {
        VolcTtsWebSocketTransport activeTransport;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Volc TTS session closed");
            }
            activeTransport = transport;
            if (activeTransport == null) {
                throw new IllegalStateException("Volc TTS transport not ready");
            }
        }
        activeTransport.sendBinary(frame);
    }

    private int resolveSampleRate() {
        int downlink = audioParams().downlinkSampleRate();
        return downlink > 0 ? downlink : 16000;
    }

    private void awaitConnectionStarted() throws InterruptedException {
        synchronized (lock) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs());
            while (!connectionStarted && !failed && !closed && System.nanoTime() < deadline) {
                long waitMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (waitMs <= 0) {
                    break;
                }
                lock.wait(waitMs);
            }
            if (closed) {
                throw new IllegalStateException(serverError == null ? "tts_closed" : serverError);
            }
            if (!connectionStarted) {
                throw new IllegalStateException(serverError == null ? "tts_connect_timeout" : serverError);
            }
        }
    }

    private void awaitSessionStarted() throws InterruptedException {
        synchronized (lock) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs());
            while (!sessionStarted && !failed && !closed && System.nanoTime() < deadline) {
                long waitMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (waitMs <= 0) {
                    break;
                }
                lock.wait(waitMs);
            }
            if (closed) {
                throw new IllegalStateException(serverError == null ? "tts_closed" : serverError);
            }
            if (!sessionStarted) {
                throw new IllegalStateException(serverError == null ? "tts_session_timeout" : serverError);
            }
        }
    }

    private void awaitSessionFinished() throws InterruptedException {
        synchronized (lock) {
            long stallNs = TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs()));
            long lastProgressAt = System.nanoTime();
            long lastSeenPcm = lastPcmNanos();
            while (!sessionFinished && !failed && !closed) {
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
            if (closed && !sessionFinished) {
                throw new IllegalStateException(serverError == null ? "tts_closed" : serverError);
            }
            if (!sessionFinished && !failed && !closed) {
                log.warn("Volc TTS finish stalled, keep receiving PCM session={} connectId={} stallMs={}",
                        sessionId, connectId(), timeoutMs());
            }
        }
    }

    @Override
    public boolean isUpstreamFinished() {
        synchronized (lock) {
            return sessionFinished;
        }
    }

    private void waitForAudio(int waitMs) throws InterruptedException {
        if (waitMs <= 0) {
            return;
        }
        synchronized (lock) {
            if (!awaitingAudio) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
            while (awaitingAudio && !closed && !failed && System.nanoTime() < deadline) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    break;
                }
                lock.wait(remaining);
            }
            if (awaitingAudio && System.nanoTime() >= deadline) {
                awaitingAudio = false;
            }
        }
    }

    private void onTransportMessage(byte[] message) {
        VolcTtsMessage response;
        try {
            response = VolcTtsProtocol.parse(message);
        } catch (IOException ex) {
            synchronized (lock) {
                failed = true;
                serverError = "tts_parse_failed";
                log.error("Volc TTS failed to parse response session={} connectId={} logId={}",
                        sessionId, connectId(), logId(), ex);
                lock.notifyAll();
            }
            return;
        }

        byte[] audioPayload = null;
        synchronized (lock) {
            if (response.isErrorFrame()) {
                failed = true;
                serverError = response.errorMessage() == null ? "tts_error" : response.errorMessage();
                log.error("Volc TTS server error code={} session={} connectId={} logId={}",
                        response.errorCode(), sessionId, connectId(), logId());
                lock.notifyAll();
                return;
            }
            if (response.isConnectionStarted()) {
                connectionStarted = true;
            }
            if (response.isConnectionFailed()) {
                failed = true;
                serverError = response.errorMessage() == null ? "tts_connect_failed" : response.errorMessage();
                log.error("Volc TTS connection failed session={} connectId={} logId={} message={}",
                        sessionId, connectId(), logId(), serverError);
            }
            if (response.isSessionStarted()) {
                sessionStarted = true;
            }
            if (response.isAudioResponse() && response.payload().length > 0 && !failed && !closed) {
                audioPayload = response.payload();
            }
            if (response.isSessionFailed()) {
                failed = true;
                serverError = response.errorMessage() == null ? "tts_session_failed" : response.errorMessage();
                log.error("Volc TTS session failed session={} connectId={} logId={} message={}",
                        sessionId, connectId(), logId(), serverError);
            }
            if (response.isSessionFinished()) {
                sessionFinished = true;
                awaitingAudio = false;
            }
            if (audioPayload == null) {
                lock.notifyAll();
            }
        }

        if (audioPayload != null) {
            try {
                // appendPcm 不得阻塞 WS 读回调
                appendPcm(audioPayload);
                synchronized (lock) {
                    if (!failed && !closed) {
                        awaitingAudio = false;
                    }
                    lock.notifyAll();
                }
            } catch (IOException ex) {
                synchronized (lock) {
                    failed = true;
                    serverError = "tts_audio_failed";
                    log.error("Volc TTS failed to buffer audio session={} connectId={} logId={}",
                            sessionId, connectId(), logId(), ex);
                    lock.notifyAll();
                }
            }
        }
    }

    private void onTransportClosed() {
        synchronized (lock) {
            if (!closed && !sessionFinished) {
                failed = true;
                if (serverError == null) {
                    serverError = "tts_connection_closed";
                }
            }
            lock.notifyAll();
        }
    }

    private void closeTransport() {
        VolcTtsWebSocketTransport activeTransport;
        synchronized (lock) {
            activeTransport = transport;
            transport = null;
        }
        if (activeTransport != null) {
            try {
                activeTransport.close();
            } catch (Exception ex) {
                log.debug("Volc TTS transport close failed session={}", sessionId, ex);
            }
        }
    }

    private int timeoutMs() {
        return speechProperties.getTts().getVolc().getSessionTimeoutMs();
    }

    private String connectId() {
        synchronized (lock) {
            return transport == null ? "-" : transport.connectId();
        }
    }

    private String logId() {
        synchronized (lock) {
            if (transport == null) {
                return "-";
            }
            String logId = transport.logId();
            return logId == null || logId.isBlank() ? transport.connectId() : logId;
        }
    }

    private static boolean isBenignInterrupt(String error) {
        return "tts_closed".equals(error);
    }
}
