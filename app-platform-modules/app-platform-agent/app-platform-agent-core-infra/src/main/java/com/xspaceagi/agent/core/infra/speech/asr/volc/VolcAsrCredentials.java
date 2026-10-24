package com.xspaceagi.agent.core.infra.speech.asr.volc;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;

public final class VolcAsrCredentials {

    private VolcAsrCredentials() {
    }

    public static void requireConfigured(SpeechProperties.Volc volc) {
        if (volc.hasApiKey() || volc.hasLegacyCredentials()) {
            return;
        }
        throw new IllegalStateException(
                "Volc ASR requires vox.asr.volc.api-key or vox.asr.volc.app-key/access-key");
    }
}
