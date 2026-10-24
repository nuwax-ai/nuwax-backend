package com.xspaceagi.agent.web.ui.ws.speech;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.tts.TtsService;
import com.xspaceagi.agent.core.infra.speech.tts.TtsStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * App 实时 TTS：JSON 文本分片 → Binary PCM16。
 * <p>
 * 文本合并 + 异步喂字/拉 PCM + 预缓冲限速下发，对齐 vox 播报管线。
 */
@Slf4j
@Component
public class AppTtsWebSocketHandler extends AbstractWebSocketHandler {

    private static final String ATTR_PLAYBACK = "appTtsPlayback";

    private final TtsService ttsService;
    private final ObjectMapper objectMapper;
    private final AppWsMessageSender messageSender;
    private final SpeechProperties speechProperties;

    public AppTtsWebSocketHandler(TtsService ttsService, ObjectMapper objectMapper,
            AppWsMessageSender messageSender, SpeechProperties speechProperties) {
        this.ttsService = ttsService;
        this.objectMapper = objectMapper;
        this.messageSender = messageSender;
        this.speechProperties = speechProperties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("App TTS WebSocket connected session={} user={}",
                session.getId(), appUserId(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            switch (type) {
                case "start" -> handleStart(session, root);
                case "text" -> handleText(session, root);
                case "flush" -> handleFlush(session);
                case "close" -> handleClose(session);
                default -> messageSender.sendError(session, "UNKNOWN_TYPE", "unknown message type: " + type);
            }
        } catch (Exception ex) {
            log.warn("App TTS invalid JSON session={}: {}", session.getId(), ex.getMessage());
            messageSender.sendError(session, "INVALID_JSON", ex.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closePlaybackQuietly(session);
        log.info("App TTS WebSocket closed session={} status={}", session.getId(), status);
    }

    private void handleStart(WebSocketSession session, JsonNode root) throws Exception {
        AppTtsPlaybackSession previous = (AppTtsPlaybackSession) session.getAttributes().get(ATTR_PLAYBACK);
        if (previous != null) {
            log.info("App TTS restart replaces active playback session={}", session.getId());
        }
        closePlaybackQuietly(session);
        int sampleRate = root.path("sample_rate").asInt(AppPcmAudioSupport.DEFAULT_SAMPLE_RATE);
        int frameMs = root.path("frame_duration_ms").asInt(AppPcmAudioSupport.DEFAULT_FRAME_MS);
        AudioParams audioParams = AppPcmAudioSupport.pcm16Params(sampleRate, frameMs);
        TtsStream stream = ttsService.openStream(audioParams);
        AppTtsPlaybackSession playback = new AppTtsPlaybackSession(
                session, messageSender, ttsService, speechProperties.getTts(), audioParams, stream);
        session.getAttributes().put(ATTR_PLAYBACK, playback);
        playback.start();
        messageSender.sendJson(session, Map.of("type", "tts_ready"));
        log.info("App TTS started session={} sampleRate={} frameMs={}",
                session.getId(), sampleRate, frameMs);
    }

    private void handleText(WebSocketSession session, JsonNode root) {
        AppTtsPlaybackSession playback = playbackOf(session);
        if (playback == null) {
            messageSender.sendError(session, "TTS_NOT_STARTED", "send start before text");
            return;
        }
        String text = root.path("text").asText("");
        boolean flush = root.path("flush").asBoolean(false);
        playback.offerText(text, flush);
    }

    private void handleFlush(WebSocketSession session) {
        AppTtsPlaybackSession playback = playbackOf(session);
        if (playback == null) {
            messageSender.sendError(session, "TTS_NOT_STARTED", "send start before flush");
            return;
        }
        playback.flush();
    }

    private void handleClose(WebSocketSession session) throws Exception {
        closePlaybackQuietly(session);
        messageSender.sendJson(session, Map.of("type", "tts_closed"));
    }

    private void closePlaybackQuietly(WebSocketSession session) {
        AppTtsPlaybackSession playback = (AppTtsPlaybackSession) session.getAttributes().remove(ATTR_PLAYBACK);
        if (playback != null) {
            playback.close();
        }
    }

    private static AppTtsPlaybackSession playbackOf(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_PLAYBACK);
        return value instanceof AppTtsPlaybackSession playback ? playback : null;
    }

    private static Long appUserId(WebSocketSession session) {
        Object user = session.getAttributes().get(AppBearerHandshakeInterceptor.ATTR_APP_USER);
        if (user instanceof com.xspaceagi.system.application.dto.UserDto view) {
            return view.getId();
        }
        return null;
    }
}
