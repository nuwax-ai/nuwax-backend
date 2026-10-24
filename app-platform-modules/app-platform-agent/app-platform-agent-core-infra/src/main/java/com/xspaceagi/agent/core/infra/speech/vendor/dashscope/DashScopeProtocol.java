package com.xspaceagi.agent.core.infra.speech.vendor.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DashScopeProtocol {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DashScopeProtocol() {
    }

    public static String buildRunTaskAsr(String taskId, String model, String format, int sampleRate,
            int maxSentenceSilenceMs) throws IOException {
        return buildRunTaskAsr(taskId, model, format, sampleRate, maxSentenceSilenceMs, null);
    }

    public static String buildRunTaskAsr(String taskId, String model, String format, int sampleRate,
            int maxSentenceSilenceMs, Double speechNoiseThreshold) throws IOException {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", format);
        parameters.put("sample_rate", sampleRate);
        if (maxSentenceSilenceMs > 0) {
            parameters.put("max_sentence_silence", maxSentenceSilenceMs);
        }
        if (speechNoiseThreshold != null) {
            double clamped = Math.max(-1.0, Math.min(1.0, speechNoiseThreshold));
            parameters.put("speech_noise_threshold", clamped);
        }
        return buildRunTask(taskId, "audio", "asr", "recognition", model, parameters);
    }

    public static String buildRunTaskTts(String taskId, String model, String voice, String format, int sampleRate)
            throws IOException {
        return buildRunTaskTts(taskId, model, voice, format, sampleRate, 50, 1.0, 1.0);
    }

    public static String buildRunTaskTts(String taskId, String model, String voice, String format, int sampleRate,
            int volume, double rate, double pitch) throws IOException {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("text_type", "PlainText");
        parameters.put("voice", voice);
        parameters.put("format", format);
        parameters.put("sample_rate", sampleRate);
        parameters.put("volume", clampInt(volume, 0, 100));
        parameters.put("rate", clampDouble(rate, 0.5, 2.0));
        parameters.put("pitch", clampDouble(pitch, 0.5, 2.0));
        parameters.put("enable_ssml", false);
        return buildRunTask(taskId, "audio", "tts", "SpeechSynthesizer", model, parameters);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return 1.0;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static String buildContinueTask(String taskId, String text) throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.putObject("header")
                .put("action", "continue-task")
                .put("task_id", taskId)
                .put("streaming", "duplex");
        root.putObject("payload")
                .putObject("input")
                .put("text", text == null ? "" : text);
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    public static String buildFinishTask(String taskId) throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.putObject("header")
                .put("action", "finish-task")
                .put("task_id", taskId)
                .put("streaming", "duplex");
        root.putObject("payload").putObject("input");
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    public static DashScopeEvent parseEvent(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return new DashScopeEvent("", "", "", "empty_event", null);
        }
        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode header = root.path("header");
        String event = header.path("event").asText("");
        String taskId = header.path("task_id").asText("");
        String errorCode = header.path("error_code").asText("");
        String errorMessage = header.path("error_message").asText("");
        JsonNode payload = root.path("payload");
        if (payload.isMissingNode() || payload.isNull()) {
            payload = null;
        }
        return new DashScopeEvent(event, taskId, errorCode, errorMessage, payload);
    }

    private static String buildRunTask(String taskId, String taskGroup, String task, String function, String model,
            Map<String, Object> parameters) throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.putObject("header")
                .put("action", "run-task")
                .put("task_id", taskId)
                .put("streaming", "duplex");
        ObjectNode payload = root.putObject("payload");
        payload.put("task_group", taskGroup);
        payload.put("task", task);
        payload.put("function", function);
        payload.put("model", model);
        payload.set("parameters", OBJECT_MAPPER.valueToTree(parameters));
        payload.putObject("input");
        return OBJECT_MAPPER.writeValueAsString(root);
    }
}
