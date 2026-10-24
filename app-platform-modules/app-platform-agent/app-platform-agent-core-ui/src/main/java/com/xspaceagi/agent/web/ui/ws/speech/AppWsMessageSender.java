package com.xspaceagi.agent.web.ui.ws.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

@Component
public class AppWsMessageSender {

    private static final Logger log = LoggerFactory.getLogger(AppWsMessageSender.class);

    private final ObjectMapper objectMapper;

    public AppWsMessageSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void sendJson(WebSocketSession session, Map<String, Object> message) throws IOException {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }
        log.debug("TX app-ws type={} session={}", message.get("type"), session.getId());
    }

    public void sendBinary(WebSocketSession session, byte[] payload) throws IOException {
        if (session == null || !session.isOpen() || payload == null || payload.length == 0) {
            return;
        }
        synchronized (session) {
            session.sendMessage(new BinaryMessage(payload));
        }
    }

    public void sendError(WebSocketSession session, String code, String message) {
        try {
            sendJson(session, Map.of(
                    "type", "error",
                    "code", code == null ? "ERROR" : code,
                    "message", message == null ? "" : message));
        } catch (IOException ex) {
            log.debug("Failed to send app-ws error session={}: {}", session.getId(), ex.getMessage());
        }
    }
}
