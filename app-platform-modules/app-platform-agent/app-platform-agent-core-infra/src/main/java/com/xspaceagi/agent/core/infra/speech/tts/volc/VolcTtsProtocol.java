package com.xspaceagi.agent.core.infra.speech.tts.volc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class VolcTtsProtocol {

    public static final int PROTOCOL_VERSION = 0x1;
    public static final int HEADER_SIZE_VALUE = 0x1;

    public static final int MESSAGE_TYPE_FULL_CLIENT = 0x1;
    public static final int MESSAGE_TYPE_AUDIO_ONLY_CLIENT = 0x2;
    public static final int MESSAGE_TYPE_FULL_SERVER = 0x9;
    public static final int MESSAGE_TYPE_AUDIO_ONLY_SERVER = 0xB;
    public static final int MESSAGE_TYPE_ERROR = 0xF;

    public static final int FLAG_WITH_EVENT = 0x4;
    public static final int FLAG_POS_SEQUENCE = 0x1;

    public static final int SERIALIZATION_JSON = 0x1;
    public static final int SERIALIZATION_NONE = 0x0;
    public static final int COMPRESSION_NONE = 0x0;

    public static final int EVENT_START_CONNECTION = 1;
    public static final int EVENT_FINISH_CONNECTION = 2;
    public static final int EVENT_CONNECTION_STARTED = 50;
    public static final int EVENT_CONNECTION_FAILED = 51;
    public static final int EVENT_CONNECTION_FINISHED = 52;

    public static final int EVENT_START_SESSION = 100;
    public static final int EVENT_FINISH_SESSION = 102;
    public static final int EVENT_SESSION_STARTED = 150;
    public static final int EVENT_SESSION_FAILED = 153;
    public static final int EVENT_SESSION_FINISHED = 152;

    public static final int EVENT_TASK_REQUEST = 200;
    public static final int EVENT_TTS_RESPONSE = 352;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private VolcTtsProtocol() {
    }

    public static byte[] buildClientEvent(int event, String sessionId, byte[] payload) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }
        int messageType = MESSAGE_TYPE_FULL_CLIENT;
        int serialization = SERIALIZATION_JSON;
        if (event == EVENT_TASK_REQUEST && payload.length == 0) {
            payload = "{}".getBytes(StandardCharsets.UTF_8);
        }
        return buildEventFrame(messageType, serialization, event, sessionId, payload);
    }

    public static VolcTtsMessage parse(byte[] message) throws IOException {
        if (message == null || message.length < 4) {
            return new VolcTtsMessage(0, 0, 0, null, null, new byte[0], "short_response");
        }

        int headerBytes = (message[0] & 0x0F) * 4;
        int messageType = (message[1] >> 4) & 0x0F;
        int flags = message[1] & 0x0F;
        int serialization = (message[2] >> 4) & 0x0F;

        ByteBuffer buffer = ByteBuffer.wrap(message, headerBytes, message.length - headerBytes)
                .order(ByteOrder.BIG_ENDIAN);

        if (messageType == MESSAGE_TYPE_ERROR) {
            int errorCode = buffer.remaining() >= 4 ? buffer.getInt() : 0;
            byte[] payload = readPayload(buffer);
            return new VolcTtsMessage(messageType, 0, errorCode, null, null, payload, decodeJsonMessage(payload));
        }

        if ((flags & FLAG_WITH_EVENT) == 0) {
            byte[] payload = readRemaining(buffer);
            return new VolcTtsMessage(messageType, 0, 0, null, null, payload, null);
        }

        if (buffer.remaining() < 4) {
            return new VolcTtsMessage(messageType, 0, 0, null, null, new byte[0], "missing_event");
        }

        int event = buffer.getInt();
        String sessionId = null;
        String connectId = null;

        if (requiresSessionId(event)) {
            sessionId = readLengthPrefixedString(buffer);
        }
        if (requiresConnectId(event)) {
            connectId = readLengthPrefixedString(buffer);
        }
        if ((flags & FLAG_POS_SEQUENCE) != 0 && buffer.remaining() >= 4) {
            buffer.getInt();
        }

        byte[] payload = readPayload(buffer);
        String errorMessage = null;
        if (serialization == SERIALIZATION_JSON && payload.length > 0
                && (event == EVENT_SESSION_FAILED || event == EVENT_CONNECTION_FAILED)) {
            errorMessage = decodeJsonMessage(payload);
        }
        return new VolcTtsMessage(messageType, event, 0, sessionId, connectId, payload, errorMessage);
    }

    public static String decodeJsonMessage(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            String message = root.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
            return root.toString();
        } catch (Exception ex) {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private static byte[] buildEventFrame(int messageType, int serialization, int event, String sessionId,
            byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 64);
        out.write((PROTOCOL_VERSION << 4) | HEADER_SIZE_VALUE);
        out.write((messageType << 4) | FLAG_WITH_EVENT);
        out.write((serialization << 4) | COMPRESSION_NONE);
        out.write(0x00);
        writeInt(out, event);
        if (requiresSessionId(event)) {
            byte[] sid = sessionId == null ? new byte[0] : sessionId.getBytes(StandardCharsets.UTF_8);
            writeInt(out, sid.length);
            out.writeBytes(sid);
        }
        writeInt(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static boolean requiresSessionId(int event) {
        return event != EVENT_START_CONNECTION
                && event != EVENT_FINISH_CONNECTION
                && event != EVENT_CONNECTION_STARTED
                && event != EVENT_CONNECTION_FAILED
                && event != EVENT_CONNECTION_FINISHED;
    }

    private static boolean requiresConnectId(int event) {
        return event == EVENT_CONNECTION_STARTED
                || event == EVENT_CONNECTION_FAILED
                || event == EVENT_CONNECTION_FINISHED;
    }

    private static byte[] readPayload(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return new byte[0];
        }
        int payloadSize = buffer.getInt();
        if (payloadSize <= 0 || buffer.remaining() < payloadSize) {
            return new byte[0];
        }
        byte[] payload = new byte[payloadSize];
        buffer.get(payload);
        return payload;
    }

    private static byte[] readRemaining(ByteBuffer buffer) {
        byte[] payload = new byte[buffer.remaining()];
        if (payload.length > 0) {
            buffer.get(payload);
        }
        return payload;
    }

    private static String readLengthPrefixedString(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return null;
        }
        int length = buffer.getInt();
        if (length <= 0 || buffer.remaining() < length) {
            return null;
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
