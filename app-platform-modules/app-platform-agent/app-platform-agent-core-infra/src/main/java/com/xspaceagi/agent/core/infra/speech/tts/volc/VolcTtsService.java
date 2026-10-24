package com.xspaceagi.agent.core.infra.speech.tts.volc;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.tts.TtsService;
import com.xspaceagi.agent.core.infra.speech.tts.TtsStream;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.netty.http.client.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(prefix = "speech.tts", name = "provider", havingValue = "volc")
@AllArgsConstructor
public class VolcTtsService implements TtsService {

    private final SpeechProperties speechProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient volcTtsHttpClient;

    @Override
    public TtsStream openStream(AudioParams audioParams) {
        return new VolcTtsStreamSession(speechProperties, objectMapper, audioParams, volcTtsHttpClient);
    }
}
