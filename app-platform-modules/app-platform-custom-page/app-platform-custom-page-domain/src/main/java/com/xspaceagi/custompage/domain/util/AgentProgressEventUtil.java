package com.xspaceagi.custompage.domain.util;

import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Agent 进度 SSE 事件解析（终态判定等与前后端约定保持一致）。
 */
public final class AgentProgressEventUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AgentProgressEventUtil() {
    }

    public static boolean shouldPersist(String eventName) {
        return !"ping".equalsIgnoreCase(eventName);
    }

    public static boolean isFinalEvent(String eventName, String data) {
        if ("success".equalsIgnoreCase(eventName) || "error".equalsIgnoreCase(eventName)
                || "canceled".equalsIgnoreCase(eventName)
                || "end_turn".equalsIgnoreCase(eventName)) {
            return true;
        }
        if (!JSON.isValidObject(data)) {
            return false;
        }
        try {
            Map<String, Object> payload = OBJECT_MAPPER.readValue(data, new TypeReference<Map<String, Object>>() {
            });
            return isFinalPayload(payload);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isFinalPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        Object type = payload.get("type");
        if (type != null) {
            String typeStr = String.valueOf(type);
            if ("success".equalsIgnoreCase(typeStr) || "error".equalsIgnoreCase(typeStr)
                    || "canceled".equalsIgnoreCase(typeStr)
                    || "end_turn".equalsIgnoreCase(typeStr)) {
                return true;
            }
        }
        Object subType = payload.get("subType");
        if (subType != null && "end_turn".equalsIgnoreCase(String.valueOf(subType))) {
            return true;
        }
        Object reason = payload.get("reason");
        if (reason != null && "EndTurn".equalsIgnoreCase(String.valueOf(reason))) {
            return true;
        }
        Object dataObj = payload.get("data");
        if (dataObj instanceof Map<?, ?> dataMap) {
            Object nestedReason = dataMap.get("reason");
            if (nestedReason != null && "EndTurn".equalsIgnoreCase(String.valueOf(nestedReason))) {
                return true;
            }
            Object nestedSubType = dataMap.get("subType");
            if (nestedSubType != null && "end_turn".equalsIgnoreCase(String.valueOf(nestedSubType))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static boolean isAssistantContentTerminal(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = OBJECT_MAPPER.readValue(contentJson, new TypeReference<Map<String, Object>>() {
            });
            Object eventsObj = body.get("events");
            if (!(eventsObj instanceof List<?> events)) {
                return false;
            }
            for (Object item : events) {
                if (!(item instanceof Map<?, ?> record)) {
                    continue;
                }
                String eventName = record.get("event") == null ? null : String.valueOf(record.get("event"));
                Object data = record.get("data");
                String dataStr;
                if (data instanceof String s) {
                    dataStr = s;
                } else if (data != null) {
                    dataStr = JSON.toJSONString(data);
                } else {
                    dataStr = null;
                }
                if (isFinalEvent(eventName, dataStr)) {
                    return true;
                }
                if (data instanceof Map<?, ?> dataMap) {
                    Map<String, Object> payload = castToStringObjectMap(dataMap);
                    if (isFinalPayload(payload)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToStringObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    public static Object parseRawData(String data) {
        if (!JSON.isValidObject(data)) {
            return data;
        }
        try {
            return OBJECT_MAPPER.readValue(data, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return data;
        }
    }
}
