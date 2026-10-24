package com.xspaceagi.agent.core.infra.speech.vendor.dashscope;

import com.fasterxml.jackson.databind.JsonNode;

public record DashScopeEvent(
        String event,
        String taskId,
        String errorCode,
        String errorMessage,
        JsonNode payload) {

    public boolean isTaskStarted() {
        return "task-started".equals(event);
    }

    public boolean isTaskFinished() {
        return "task-finished".equals(event);
    }

    public boolean isTaskFailed() {
        return "task-failed".equals(event);
    }

    public boolean isResultGenerated() {
        return "result-generated".equals(event);
    }

    public String extractAsrText() {
        if (payload == null) {
            return "";
        }
        JsonNode sentence = payload.path("output").path("sentence");
        return sentence.path("text").asText("");
    }

    /** Fun-ASR 心跳包；应忽略 text。 */
    public boolean isAsrHeartbeat() {
        if (payload == null) {
            return false;
        }
        return payload.path("output").path("sentence").path("heartbeat").asBoolean(false);
    }

    /** 句序号；心跳包为 0。缺失时返回 null。 */
    public Integer extractAsrSentenceId() {
        if (payload == null) {
            return null;
        }
        JsonNode sentence = payload.path("output").path("sentence");
        if (!sentence.has("sentence_id") || !sentence.path("sentence_id").isNumber()) {
            return null;
        }
        return sentence.path("sentence_id").asInt();
    }

    /**
     * 句开始相对音频起点的毫秒（{@code begin_time}）；无法解析时返回 null。
     */
    public Integer extractAsrBeginTimeMs() {
        if (payload == null) {
            return null;
        }
        JsonNode sentence = payload.path("output").path("sentence");
        if (!sentence.has("begin_time") || !sentence.path("begin_time").isNumber()) {
            return null;
        }
        int begin = sentence.path("begin_time").asInt(Integer.MIN_VALUE);
        return begin < 0 ? null : begin;
    }

    /**
     * 句结束相对音频起点的毫秒（{@code end_time}）；无法解析时返回 null。
     * 用于会话级时长，优于单句 begin/end 差。
     */
    public Integer extractAsrEndTimeMs() {
        if (payload == null) {
            return null;
        }
        JsonNode sentence = payload.path("output").path("sentence");
        if (!sentence.has("end_time") || !sentence.path("end_time").isNumber()) {
            return null;
        }
        int end = sentence.path("end_time").asInt(Integer.MIN_VALUE);
        return end < 0 ? null : end;
    }

    /** 句级时长（毫秒）；无法从事件解析时返回 null。 */
    public Integer extractAsrDurationMs() {
        if (payload == null) {
            return null;
        }
        JsonNode sentence = payload.path("output").path("sentence");
        if (!sentence.has("begin_time") || !sentence.has("end_time")) {
            return null;
        }
        int begin = sentence.path("begin_time").asInt(Integer.MIN_VALUE);
        int end = sentence.path("end_time").asInt(Integer.MIN_VALUE);
        if (begin < 0 || end < begin) {
            return null;
        }
        return end - begin;
    }

    /**
     * 当前句是否已定稿。优先看 {@code sentence_end}；
     * 旧事件无该字段时回退为 {@code end_time} 存在。
     */
    public boolean isAsrSentenceEnd() {
        if (payload == null) {
            return false;
        }
        JsonNode sentence = payload.path("output").path("sentence");
        if (sentence.has("sentence_end")) {
            return sentence.path("sentence_end").asBoolean(false);
        }
        return sentence.path("end_time").isNumber();
    }

    public boolean isTtsSentenceSynthesis() {
        if (payload == null) {
            return false;
        }
        return "sentence-synthesis".equals(payload.path("output").path("type").asText(""));
    }

    public boolean matchesTaskId(String expectedTaskId) {
        if (expectedTaskId == null || expectedTaskId.isBlank()) {
            return false;
        }
        return taskId == null || taskId.isBlank() || expectedTaskId.equals(taskId);
    }
}
