package com.xspaceagi.agent.core.infra.speech.asr.volc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class VolcAsrProtocol {

    public static final int PROTOCOL_VERSION = 0x1;
    public static final int HEADER_SIZE_VALUE = 0x1;

    public static final int MESSAGE_TYPE_FULL_CLIENT = 0x1;
    public static final int MESSAGE_TYPE_AUDIO_ONLY = 0x2;
    public static final int MESSAGE_TYPE_SERVER_FULL = 0x9;
    public static final int MESSAGE_TYPE_SERVER_ERROR = 0xF;

    public static final int FLAG_POS_SEQUENCE = 0x1;
    public static final int FLAG_LAST_AUDIO = 0x2;

    public static final int SERIALIZATION_JSON = 0x1;
    public static final int SERIALIZATION_NONE = 0x0;
    public static final int COMPRESSION_GZIP = 0x1;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private VolcAsrProtocol() {
    }

    public static byte[] buildFullClientRequest(int sequence, byte[] jsonPayload) throws IOException {
        byte[] compressed = gzip(jsonPayload);
        return buildFrame(MESSAGE_TYPE_FULL_CLIENT, FLAG_POS_SEQUENCE, SERIALIZATION_JSON, COMPRESSION_GZIP,
                sequence, true, compressed);
    }

    public static byte[] buildAudioRequest(int sequence, byte[] pcmPayload, boolean last) throws IOException {
        byte[] compressed = gzip(pcmPayload);
        if (last) {
            return buildFrame(MESSAGE_TYPE_AUDIO_ONLY, FLAG_LAST_AUDIO, SERIALIZATION_NONE, COMPRESSION_GZIP,
                    0, false, compressed);
        }
        return buildFrame(MESSAGE_TYPE_AUDIO_ONLY, FLAG_POS_SEQUENCE, SERIALIZATION_NONE, COMPRESSION_GZIP,
                sequence, true, compressed);
    }

    public static VolcAsrResponse parseResponse(byte[] message) throws IOException {
        if (message == null || message.length < 4) {
            return new VolcAsrResponse(0, 0, false, "", "short_response");
        }

        int headerWords = message[0] & 0x0F;
        int headerBytes = headerWords * 4;
        int messageType = (message[1] >> 4) & 0x0F;
        int flags = message[1] & 0x0F;
        int serialization = (message[2] >> 4) & 0x0F;
        int compression = message[2] & 0x0F;

        ByteBuffer buffer = ByteBuffer.wrap(message, headerBytes, message.length - headerBytes)
                .order(ByteOrder.BIG_ENDIAN);
        boolean lastPackage = (flags & FLAG_LAST_AUDIO) != 0;

        if ((flags & FLAG_POS_SEQUENCE) != 0 && buffer.remaining() >= 4) {
            buffer.getInt();
        }
        if ((flags & 0x4) != 0 && buffer.remaining() >= 4) {
            buffer.getInt();
        }

        int errorCode = 0;
        String errorMessage = null;
        byte[] payload = new byte[0];

        if (messageType == MESSAGE_TYPE_SERVER_ERROR) {
            if (buffer.remaining() >= 8) {
                errorCode = buffer.getInt();
                int errorSize = buffer.getInt();
                if (errorSize > 0 && buffer.remaining() >= errorSize) {
                    byte[] errorBytes = new byte[errorSize];
                    buffer.get(errorBytes);
                    errorMessage = decodeErrorMessage(errorBytes, serialization, compression);
                } else if (errorSize > 0) {
                    errorMessage = "truncated_error_payload";
                }
            } else if (buffer.remaining() >= 4) {
                errorCode = buffer.getInt();
                errorMessage = "truncated_error_frame";
            } else {
                errorMessage = "empty_error_frame";
            }
            return new VolcAsrResponse(messageType, errorCode, lastPackage, "", errorMessage);
        }

        if (messageType == MESSAGE_TYPE_SERVER_FULL && buffer.remaining() >= 4) {
            int payloadSize = buffer.getInt();
            if (payloadSize > 0 && buffer.remaining() >= payloadSize) {
                payload = new byte[payloadSize];
                buffer.get(payload);
            }
        }

        if (payload.length == 0) {
            return new VolcAsrResponse(messageType, errorCode, lastPackage, "", errorMessage);
        }

        if (compression == COMPRESSION_GZIP) {
            payload = gunzip(payload);
        }

        if (serialization != SERIALIZATION_JSON) {
            return new VolcAsrResponse(messageType, errorCode, lastPackage, "", errorMessage);
        }

        JsonNode root = OBJECT_MAPPER.readTree(payload);
        JsonNode result = root.path("result");
        String text = result.path("text").asText("");
        if (text.isBlank()) {
            text = root.path("text").asText("");
        }
        Integer durationMs = extractDurationMs(result, root);
        List<VolcUtterance> utterances = extractUtterances(result.path("utterances"));
        if (utterances.isEmpty()) {
            utterances = extractUtterances(root.path("utterances"));
        }
        return new VolcAsrResponse(messageType, errorCode, lastPackage, text, errorMessage, durationMs, utterances);
    }

    static List<VolcUtterance> extractUtterances(JsonNode utterances) {
        if (utterances == null || !utterances.isArray() || utterances.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<VolcUtterance> out = new java.util.ArrayList<>();
        for (JsonNode utterance : utterances) {
            String text = utterance.path("text").asText("");
            if (text == null || text.isBlank()) {
                continue;
            }
            boolean definite = !utterance.has("definite") || utterance.path("definite").asBoolean(true);
            Integer beginMs = utterance.has("start_time") ? utterance.path("start_time").asInt() : null;
            Integer endMs = utterance.has("end_time") ? utterance.path("end_time").asInt() : null;
            if (beginMs != null && beginMs < 0) {
                beginMs = null;
            }
            if (endMs != null && endMs < 0) {
                endMs = null;
            }
            out.add(new VolcUtterance(text.trim(), beginMs, endMs, definite));
        }
        return List.copyOf(out);
    }

    /**
     * 优先用 utterances 的 start/end（毫秒）跨度；否则回退 audio_info/additions.duration。
     */
    static Integer extractDurationMs(JsonNode result, JsonNode root) {
        Integer fromUtterances = durationFromUtterances(result.path("utterances"));
        if (fromUtterances != null) {
            return fromUtterances;
        }
        fromUtterances = durationFromUtterances(root.path("utterances"));
        if (fromUtterances != null) {
            return fromUtterances;
        }
        Integer fromAdditions = parseDurationField(result.path("additions").path("duration"));
        if (fromAdditions != null) {
            return fromAdditions;
        }
        return parseDurationField(root.path("audio_info").path("duration"));
    }

    private static Integer durationFromUtterances(JsonNode utterances) {
        if (utterances == null || !utterances.isArray() || utterances.isEmpty()) {
            return null;
        }
        int minStart = Integer.MAX_VALUE;
        int maxEnd = Integer.MIN_VALUE;
        boolean found = false;
        for (JsonNode utterance : utterances) {
            if (utterance.has("definite") && !utterance.path("definite").asBoolean(true)) {
                continue;
            }
            if (!utterance.has("start_time") || !utterance.has("end_time")) {
                continue;
            }
            int start = utterance.path("start_time").asInt(Integer.MIN_VALUE);
            int end = utterance.path("end_time").asInt(Integer.MIN_VALUE);
            if (start < 0 || end < start) {
                continue;
            }
            found = true;
            minStart = Math.min(minStart, start);
            maxEnd = Math.max(maxEnd, end);
        }
        return found ? maxEnd - minStart : null;
    }

    private static Integer parseDurationField(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            int value = node.asInt(-1);
            return value >= 0 ? value : null;
        }
        String text = node.asText("");
        if (text.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String decodeErrorMessage(byte[] errorBytes, int serialization, int compression) throws IOException {
        byte[] payload = errorBytes;
        if (compression == COMPRESSION_GZIP) {
            payload = gunzip(payload);
        }
        if (serialization == SERIALIZATION_JSON) {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            String message = firstText(root, "message", "error_message", "msg", "error");
            if (message != null) {
                return message;
            }
            String code = firstText(root, "code", "error_code");
            if (code != null) {
                return code;
            }
            return root.toString();
        }
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String firstText(JsonNode root, String... fields) {
        if (root == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            String value = root.path(field).asText("");
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static byte[] buildFrame(int messageType, int flags, int serialization, int compression,
            int sequence, boolean includeSequence, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 16);
        out.write((PROTOCOL_VERSION << 4) | HEADER_SIZE_VALUE);
        out.write((messageType << 4) | flags);
        out.write((serialization << 4) | compression);
        out.write(0x00);
        if (includeSequence) {
            writeInt(out, sequence);
        }
        writeInt(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(input);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] input) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input));
                ByteArrayOutputStream out = new ByteArrayOutputStream(input.length)) {
            gzip.transferTo(out);
            return out.toByteArray();
        }
    }
}
