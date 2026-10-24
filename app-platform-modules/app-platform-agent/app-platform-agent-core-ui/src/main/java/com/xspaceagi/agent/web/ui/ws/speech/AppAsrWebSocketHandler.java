package com.xspaceagi.agent.web.ui.ws.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.speech.asr.AsrListener;
import com.xspaceagi.agent.core.infra.speech.asr.AsrService;
import com.xspaceagi.agent.core.infra.speech.asr.AsrSessionFactory;
import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App 实时 ASR：JSON 控制 + Binary PCM16。
 * <p>
 * 流式 partial/sentence 推送 + stop 出全文；百炼空闲超时静默重建对齐 vox。
 */
@Slf4j
@Component
public class AppAsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final String ATTR_LISTEN = "appAsrListen";

    private final AsrSessionFactory asrSessionFactory;
    private final ObjectMapper objectMapper;
    private final AppWsMessageSender messageSender;
    private final SpeechProperties speechProperties;

    public AppAsrWebSocketHandler(AsrSessionFactory asrSessionFactory, ObjectMapper objectMapper,
            AppWsMessageSender messageSender, SpeechProperties speechProperties) {
        this.asrSessionFactory = asrSessionFactory;
        this.objectMapper = objectMapper;
        this.messageSender = messageSender;
        this.speechProperties = speechProperties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("App ASR WebSocket connected session={} user={}",
                session.getId(), appUserId(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            switch (type) {
                case "start" -> handleStart(session, root);
                case "stop" -> handleStop(session);
                default -> messageSender.sendError(session, "UNKNOWN_TYPE", "unknown message type: " + type);
            }
        } catch (Exception ex) {
            log.warn("App ASR invalid JSON session={}: {}", session.getId(), ex.getMessage());
            messageSender.sendError(session, "INVALID_JSON", ex.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        AppAsrListenSession listen = (AppAsrListenSession) session.getAttributes().get(ATTR_LISTEN);
        if (listen == null) {
            messageSender.sendError(session, "ASR_NOT_STARTED", "send start before audio");
            return;
        }
        try {
            byte[] payload = toByteArray(message.getPayload());
            if (payload.length == 0) {
                return;
            }
            listen.feed(payload);
        } catch (Exception ex) {
            log.warn("App ASR feed failed session={}: {}", session.getId(), ex.getMessage());
            messageSender.sendError(session, "ASR_FEED_FAILED", ex.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeListenQuietly(session);
        log.info("App ASR WebSocket closed session={} status={}", session.getId(), status);
    }

    private void handleStart(WebSocketSession session, JsonNode root) throws Exception {
        AppAsrListenSession previous = (AppAsrListenSession) session.getAttributes().get(ATTR_LISTEN);
        if (previous != null) {
            log.info("App ASR restart replaces active listen session={}", session.getId());
        }
        closeListenQuietly(session);
        int sampleRate = root.path("sample_rate").asInt(AppPcmAudioSupport.DEFAULT_SAMPLE_RATE);
        int frameMs = root.path("frame_duration_ms").asInt(AppPcmAudioSupport.DEFAULT_FRAME_MS);
        AudioParams audioParams = AppPcmAudioSupport.pcm16Params(sampleRate, frameMs);
        AsrService asr = asrSessionFactory.createSession(audioParams);
        AtomicReference<String> prepareError = new AtomicReference<>();
        asr.setListener(new AsrListener() {
            @Override
            public void onError(String error) {
                prepareError.compareAndSet(null, error == null || error.isBlank() ? "asr_error" : error);
            }
        });
        asr.prepare();
        if (prepareError.get() != null) {
            try {
                asr.close();
            } catch (Exception ex) {
                log.debug("App ASR prepare-failed close session={}: {}", session.getId(), ex.getMessage());
            }
            messageSender.sendError(session, "ASR_START_FAILED", prepareError.get());
            log.warn("App ASR prepare failed session={} error={}", session.getId(), prepareError.get());
            return;
        }
        AtomicReference<AppAsrListenSession> listenRef = new AtomicReference<>();
        AppAsrListenSession listen = new AppAsrListenSession(
                session, messageSender, asrSessionFactory, speechProperties.getAsr(), audioParams, asr,
                () -> {
                    AppAsrListenSession self = listenRef.get();
                    Object current = session.getAttributes().get(ATTR_LISTEN);
                    if (self != null && current == self) {
                        session.getAttributes().remove(ATTR_LISTEN);
                    }
                });
        listenRef.set(listen);
        session.getAttributes().put(ATTR_LISTEN, listen);
        messageSender.sendJson(session, Map.of("type", "asr_ready"));
        log.info("App ASR started session={} sampleRate={} frameMs={}",
                session.getId(), sampleRate, frameMs);
    }

    private void handleStop(WebSocketSession session) {
        AppAsrListenSession listen = (AppAsrListenSession) session.getAttributes().remove(ATTR_LISTEN);
        if (listen == null) {
            messageSender.sendError(session, "ASR_NOT_STARTED", "no active asr session");
            return;
        }
        listen.requestStop();
    }

    private void closeListenQuietly(WebSocketSession session) {
        AppAsrListenSession listen = (AppAsrListenSession) session.getAttributes().remove(ATTR_LISTEN);
        if (listen != null) {
            try {
                listen.close();
            } catch (Exception ex) {
                log.debug("App ASR cleanup failed session={}: {}", session.getId(), ex.getMessage());
            }
        }
    }

    private static Long appUserId(WebSocketSession session) {
        Object user = session.getAttributes().get(AppBearerHandshakeInterceptor.ATTR_APP_USER);
        if (user instanceof com.xspaceagi.system.application.dto.UserDto view) {
            return view.getId();
        }
        return null;
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }
}
