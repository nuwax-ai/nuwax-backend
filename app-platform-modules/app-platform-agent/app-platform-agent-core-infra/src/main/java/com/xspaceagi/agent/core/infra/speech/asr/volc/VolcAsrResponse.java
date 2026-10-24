package com.xspaceagi.agent.core.infra.speech.asr.volc;

import java.util.List;

public record VolcAsrResponse(
        int messageType,
        int errorCode,
        boolean lastPackage,
        String text,
        String errorMessage,
        Integer durationMs,
        List<VolcUtterance> utterances) {

    public VolcAsrResponse {
        utterances = utterances == null ? List.of() : List.copyOf(utterances);
    }

    public VolcAsrResponse(int messageType, int errorCode, boolean lastPackage, String text, String errorMessage) {
        this(messageType, errorCode, lastPackage, text, errorMessage, null, List.of());
    }

    public VolcAsrResponse(int messageType, int errorCode, boolean lastPackage, String text, String errorMessage,
            Integer durationMs) {
        this(messageType, errorCode, lastPackage, text, errorMessage, durationMs, List.of());
    }

    public boolean isError() {
        return messageType == VolcAsrProtocol.MESSAGE_TYPE_SERVER_ERROR;
    }
}
