package com.xspaceagi.agent.core.infra.speech.asr.volc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.speech.asr.AsrListener;
import com.xspaceagi.agent.core.infra.speech.asr.AsrResult;
import com.xspaceagi.agent.core.infra.speech.asr.AsrSentence;
import com.xspaceagi.agent.core.infra.speech.asr.AsrService;
import com.xspaceagi.agent.core.infra.speech.asr.PcmFrameAccumulator;
import com.xspaceagi.agent.core.infra.speech.audio.OpusDecoder;
import com.xspaceagi.agent.core.infra.speech.audio.Pcm16Resampler;
import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.protocol.NaFrame;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 火山实时 ASR。流式语义对齐百炼 Fun-ASR：
 * <ul>
 *   <li>{@code end_window_size}（≥200）+ {@code force_to_speech_time} ↔ 百炼 {@code max_sentence_silence}</li>
 *   <li>{@code definite} 分句 → {@link AsrListener#onSentenceFinal}；未定稿 → {@link AsrListener#onPartial}</li>
 *   <li>{@link #flush()} 返回已定稿句 + 未定稿 partial 拼装结果</li>
 * </ul>
 */
@Slf4j
public class VolcAsrService implements AsrService {

    private static final int VOLC_SAMPLE_RATE = 16000;

    private final SpeechProperties.Asr asr;
    private final SpeechProperties.Volc volc;
    private final ObjectMapper objectMapper;
    private final AudioParams audioParams;
    private final PcmFrameAccumulator pcmAccumulator;
    private final HttpClient httpClient;
    private final Object stateLock = new Object();

    private VolcAsrWebSocketTransport transport;
    private int sequence = 1;
    private boolean fullClientRequestSent;
    private boolean sentAudio;
    private boolean sendFailed;
    private boolean closed;
    /** 已定稿分句文本累计（与百炼 committedText 同角色）。 */
    private final StringBuilder committedText = new StringBuilder();
    private final List<AsrSentence> committedSentences = new ArrayList<>();
    private String partialText = "";
    private Integer latestDurationMs;
    private boolean lastPackageReceived;
    private String serverError;
    private CountDownLatch resultLatch;
    private AsrListener listener;
    /** 已向 listener 发出的 definite 分句数（按响应内 utterances 全量列表下标推进）。 */
    private int emittedDefiniteCount;

    public VolcAsrService(SpeechProperties speechProperties, OpusDecoder opusDecoder, ObjectMapper objectMapper,
            AudioParams audioParams, HttpClient volcAsrHttpClient) {
        this.asr = speechProperties.getAsr();
        this.volc = asr.getVolc();
        this.objectMapper = objectMapper;
        this.audioParams = audioParams;
        this.audioParams.requireValid();
        this.pcmAccumulator = new PcmFrameAccumulator(audioParams, opusDecoder);
        this.httpClient = volcAsrHttpClient;
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
            ensureTransport();
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
                    return AsrResult.failure(failureDetail("asr_send_failed", serverError));
                }
                if (pcmAccumulator.size() == 0 && !sentAudio) {
                    return AsrResult.failure("no_speech");
                }
                resultLatch = new CountDownLatch(1);
                lastPackageReceived = false;
                serverError = null;
                awaitLatch = resultLatch;
            }
            try {
                pumpBufferedAudio(true);
            } catch (Exception ex) {
                log.warn("Volc ASR flush failed connectId={} logId={}", connectId(), logId(), ex);
                return AsrResult.failure(ex.getMessage() == null || ex.getMessage().isBlank()
                        ? "asr_failed" : truncate(ex.getMessage()));
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
        VolcAsrWebSocketTransport toClose;
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
                log.debug("Volc ASR transport close failed connectId={}", toClose.connectId(), ex);
            }
        }
    }

    private AsrResult awaitResult(CountDownLatch latch) throws InterruptedException {
        if (latch == null) {
            return AsrResult.failure("asr_failed");
        }
        int timeoutMs = volc.getResultTimeoutMs();
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
                return AsrResult.failure(truncate(serverError));
            }
            String text = assembledTranscript();
            if (text == null || text.isBlank()) {
                return AsrResult.failure("no_speech");
            }
            return AsrResult.success(text, latestDurationMs, snapshotSentences(true));
        }
    }

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

    private String incrementalBeyondCommitted(String fullText) {
        if (fullText == null) {
            return "";
        }
        String full = fullText.trim();
        if (full.isEmpty()) {
            return "";
        }
        String committed = committedText.toString();
        if (committed.isEmpty()) {
            return full;
        }
        if (full.startsWith(committed)) {
            return full.substring(committed.length()).trim();
        }
        String committedTrimmed = committed.trim();
        if (!committedTrimmed.isEmpty() && full.startsWith(committedTrimmed)) {
            return full.substring(committedTrimmed.length()).trim();
        }
        return "";
    }

    private void ensureTransport() throws Exception {
        synchronized (stateLock) {
            if (closed || sendFailed) {
                throw new IllegalStateException("Volc ASR session unavailable");
            }
        }
        initTransportIfNeeded();
        VolcAsrWebSocketTransport active = transport;
        if (active == null) {
            throw new IllegalStateException("Volc ASR transport unavailable");
        }
        active.ensureOpen();
        sendFullClientRequestIfNeeded(active);
    }

    private void initTransportIfNeeded() throws Exception {
        if (transport != null) {
            return;
        }
        synchronized (stateLock) {
            if (transport != null || closed || sendFailed) {
                return;
            }
            VolcAsrCredentials.requireConfigured(volc);
            transport = new VolcAsrWebSocketTransport(httpClient, volc,
                    this::onTransportMessage, this::onTransportClosed);
        }
    }

    private void sendFullClientRequestIfNeeded(VolcAsrWebSocketTransport active) throws Exception {
        byte[] request;
        int endWindowSizeMs;
        int forceToSpeechTimeMs;
        synchronized (stateLock) {
            if (fullClientRequestSent || closed || sendFailed) {
                return;
            }
            endWindowSizeMs = volc.resolveEndWindowSizeMs(asr);
            forceToSpeechTimeMs = volc.resolveForceToSpeechTimeMs();
            Map<String, Object> requestParams = new LinkedHashMap<>();
            requestParams.put("model_name", "bigmodel");
            requestParams.put("enable_itn", true);
            requestParams.put("enable_punc", true);
            requestParams.put("show_utterances", true);
            // 与百炼 max_sentence_silence 同语义；须配合 force_to_speech_time，否则短句前约 10s 不定稿
            requestParams.put("end_window_size", endWindowSizeMs);
            requestParams.put("force_to_speech_time", forceToSpeechTimeMs);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("audio", Map.of(
                    "format", "pcm",
                    "codec", "raw",
                    "rate", VOLC_SAMPLE_RATE,
                    "bits", 16,
                    "channel", 1));
            payload.put("request", requestParams);
            byte[] json = objectMapper.writeValueAsBytes(payload);
            request = VolcAsrProtocol.buildFullClientRequest(sequence, json);
            sequence++;
        }
        active.sendBinary(request);
        synchronized (stateLock) {
            fullClientRequestSent = true;
        }
        log.info("Volc ASR connected connectId={} logId={} endpoint={} endWindowSizeMs={} forceToSpeechTimeMs={}",
                active.connectId(), active.logId(), volc.getEndpoint(), endWindowSizeMs, forceToSpeechTimeMs);
    }

    private void pumpBufferedAudio(boolean terminal) throws Exception {
        ensureTransport();
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
            chunk = resampleForVolc(chunk);
            sendAudioChunk(chunk, last);
            if (last) {
                return;
            }
        }
    }

    private void onTransportClosed() {
        AsrListener notify = null;
        String error = null;
        synchronized (stateLock) {
            if (resultLatch != null && !lastPackageReceived && serverError == null && sentAudio) {
                signalDone();
            }
            if (!closed && !lastPackageReceived && !sendFailed) {
                sendFailed = true;
                if (serverError == null || serverError.isBlank()) {
                    serverError = "asr_connection_closed";
                }
                notify = listener;
                error = serverError;
                signalDone();
            }
        }
        notifyError(notify, error);
    }

    private void sendAudioChunk(byte[] pcm, boolean last) throws Exception {
        int seq;
        synchronized (stateLock) {
            if (sendFailed || closed) {
                return;
            }
            seq = sequence;
            if (!last) {
                sequence++;
            }
            sentAudio = sentAudio || pcm.length > 0 || last;
        }
        VolcAsrWebSocketTransport active = transport;
        if (active == null) {
            throw new IllegalStateException("Volc ASR websocket not connected");
        }
        active.sendBinary(VolcAsrProtocol.buildAudioRequest(seq, pcm, last));
        if (last) {
            log.info("Volc ASR sent terminal audio chunk bytes={} connectId={} logId={}",
                    pcm.length, connectId(), logId());
        }
    }

    private int chunkBytes() {
        int chunkMs = Math.max(100, volc.getChunkMs());
        return audioParams.uplinkSampleRate() * chunkMs / 1000 * 2;
    }

    private byte[] resampleForVolc(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return pcm == null ? new byte[0] : pcm;
        }
        int uplinkRate = audioParams.uplinkSampleRate();
        if (uplinkRate == VOLC_SAMPLE_RATE) {
            return pcm;
        }
        return Pcm16Resampler.resample(pcm, uplinkRate, VOLC_SAMPLE_RATE);
    }

    private void onTransportMessage(byte[] message) {
        AsrListener notify = null;
        List<AsrSentence> finals = List.of();
        String partial = null;
        String error = null;
        synchronized (stateLock) {
            try {
                VolcAsrResponse response = VolcAsrProtocol.parseResponse(message);
                if (response.isError()) {
                    serverError = formatServerError(response.errorCode(), response.errorMessage());
                    log.warn("Volc ASR server error code={} message={} connectId={} logId={}",
                            response.errorCode(), serverError, connectId(), logId());
                    notify = listener;
                    error = serverError;
                    signalDone();
                } else {
                    if (response.durationMs() != null) {
                        latestDurationMs = response.durationMs();
                    }
                    List<VolcUtterance> definite = new ArrayList<>();
                    VolcUtterance lastPartial = null;
                    for (VolcUtterance utterance : response.utterances()) {
                        if (utterance.definite()) {
                            definite.add(utterance);
                        } else {
                            lastPartial = utterance;
                        }
                    }
                    if (emittedDefiniteCount < definite.size()) {
                        List<AsrSentence> newlyFinal = new ArrayList<>();
                        for (int i = emittedDefiniteCount; i < definite.size(); i++) {
                            VolcUtterance u = definite.get(i);
                            AsrSentence sentence = new AsrSentence(u.text(), u.beginMs(), u.endMs());
                            committedText.append(u.text());
                            committedSentences.add(sentence);
                            newlyFinal.add(sentence);
                        }
                        emittedDefiniteCount = definite.size();
                        finals = newlyFinal;
                        partialText = "";
                    }
                    if (lastPartial != null) {
                        partialText = lastPartial.text();
                        partial = lastPartial.text();
                    } else if (response.text() != null && !response.text().isBlank()) {
                        String incremental = incrementalBeyondCommitted(response.text());
                        if (!incremental.isEmpty()) {
                            if (!incremental.equals(partialText)) {
                                partialText = incremental;
                                partial = incremental;
                            }
                        } else if (!partialText.isEmpty()) {
                            partialText = "";
                        }
                    } else if (!finals.isEmpty() && !partialText.isEmpty()) {
                        partialText = "";
                    }
                    notify = listener;
                    log.debug("Volc ASR assembled={} durationMs={} connectId={} logId={} newFinals={}",
                            assembledTranscript(), latestDurationMs, connectId(), logId(), finals.size());
                    if (response.lastPackage()) {
                        lastPackageReceived = true;
                        signalDone();
                    }
                }
            } catch (Exception ex) {
                log.warn("Volc ASR failed to parse response connectId={} logId={}", connectId(), logId(), ex);
                serverError = "asr_parse_failed";
                notify = listener;
                error = serverError;
                signalDone();
            }
        }
        notifyListener(notify, finals, partial, error);
    }

    private void notifyListener(AsrListener listener, List<AsrSentence> finals, String partial, String error) {
        if (listener == null) {
            return;
        }
        try {
            if (error != null && !error.isBlank()) {
                listener.onError(error);
                return;
            }
            for (AsrSentence sentence : finals) {
                listener.onSentenceFinal(sentence);
            }
            if (partial != null && !partial.isBlank()) {
                listener.onPartial(partial);
            }
        } catch (Exception ex) {
            log.warn("Volc ASR listener callback failed", ex);
        }
    }

    private static void notifyError(AsrListener listener, String error) {
        if (listener == null || error == null || error.isBlank()) {
            return;
        }
        try {
            listener.onError(error);
        } catch (Exception ex) {
            log.warn("Volc ASR onError callback failed", ex);
        }
    }

    private void markSendFailed(Exception ex) {
        AsrListener notify;
        String error;
        synchronized (stateLock) {
            sendFailed = true;
            log.warn("Volc ASR send failed connectId={} logId={}", connectId(), logId(), ex);
            if (serverError == null || serverError.isBlank()) {
                serverError = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? "asr_send_failed" : truncate(ex.getMessage());
            }
            notify = listener;
            error = serverError;
            signalDone();
        }
        notifyError(notify, error);
    }

    private static String formatServerError(int errorCode, String errorMessage) {
        String msg = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
        if (isEmptyAudioMessage(msg)) {
            return "no_speech";
        }
        if (looksLikeRequestTimeout(msg)) {
            return "asr_timeout";
        }
        if (errorCode != 0) {
            return msg == null
                    ? truncate("asr_error:" + errorCode)
                    : truncate("asr_error:" + errorCode + ":" + msg);
        }
        if (msg != null) {
            return truncate("asr_error:" + msg);
        }
        return "asr_error:unknown";
    }

    private static boolean isEmptyAudioMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String t = msg.trim();
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
                || "timeout".equals(t) || t.startsWith("timeout ");
    }

    private static String failureDetail(String code, String detail) {
        if (detail == null || detail.isBlank() || code.equals(detail)) {
            return code;
        }
        return truncate(detail);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private void signalDone() {
        CountDownLatch latch = resultLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    private String connectId() {
        return transport == null ? "-" : transport.connectId();
    }

    private String logId() {
        if (transport == null) {
            return "-";
        }
        String logId = transport.logId();
        return logId == null || logId.isBlank() ? transport.connectId() : logId;
    }
}
