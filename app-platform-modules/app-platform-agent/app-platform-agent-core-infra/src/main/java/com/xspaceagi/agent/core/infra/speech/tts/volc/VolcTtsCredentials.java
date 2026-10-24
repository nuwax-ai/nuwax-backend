package com.xspaceagi.agent.core.infra.speech.tts.volc;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;

public final class VolcTtsCredentials {

    private VolcTtsCredentials() {
    }

    public static void requireConfigured(SpeechProperties.VolcTts volc) {
        if (volc.hasApiKey() || volc.hasLegacyCredentials()) {
            return;
        }
        throw new IllegalStateException(
                "Volc TTS requires vox.tts.volc.api-key or vox.tts.volc.app-key/access-key");
    }
}
