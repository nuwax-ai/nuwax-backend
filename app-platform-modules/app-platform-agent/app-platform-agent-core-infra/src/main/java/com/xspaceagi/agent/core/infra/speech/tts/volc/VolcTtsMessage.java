package com.xspaceagi.agent.core.infra.speech.tts.volc;

public record VolcTtsMessage(
        int messageType,
        int event,
        int errorCode,
        String sessionId,
        String connectId,
        byte[] payload,
        String errorMessage) {

    public boolean isErrorFrame() {
        return messageType == VolcTtsProtocol.MESSAGE_TYPE_ERROR;
    }

    public boolean isAudioResponse() {
        return event == VolcTtsProtocol.EVENT_TTS_RESPONSE;
    }

    public boolean isSessionFinished() {
        return event == VolcTtsProtocol.EVENT_SESSION_FINISHED;
    }

    public boolean isSessionFailed() {
        return event == VolcTtsProtocol.EVENT_SESSION_FAILED;
    }

    public boolean isConnectionStarted() {
        return event == VolcTtsProtocol.EVENT_CONNECTION_STARTED;
    }

    public boolean isConnectionFailed() {
        return event == VolcTtsProtocol.EVENT_CONNECTION_FAILED;
    }

    public boolean isSessionStarted() {
        return event == VolcTtsProtocol.EVENT_SESSION_STARTED;
    }
}
