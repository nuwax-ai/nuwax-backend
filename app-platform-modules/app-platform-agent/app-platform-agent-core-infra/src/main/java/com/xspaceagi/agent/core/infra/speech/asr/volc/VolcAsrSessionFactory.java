package com.xspaceagi.agent.core.infra.speech.asr.volc;

import com.xspaceagi.agent.core.infra.speech.asr.AsrService;
import com.xspaceagi.agent.core.infra.speech.asr.AsrSessionFactory;
import com.xspaceagi.agent.core.infra.speech.audio.OpusDecoder;
import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.netty.http.client.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "speech.asr", name = "provider", havingValue = "volc")
@AllArgsConstructor
public class VolcAsrSessionFactory implements AsrSessionFactory {

    private final SpeechProperties speechProperties;
    private final OpusDecoder opusDecoder;
    private final ObjectMapper objectMapper;
    private final HttpClient volcAsrHttpClient;

    @Override
    public AsrService createSession(AudioParams audioParams) {
        return new VolcAsrService(speechProperties, opusDecoder, objectMapper, audioParams, volcAsrHttpClient);
    }
}
