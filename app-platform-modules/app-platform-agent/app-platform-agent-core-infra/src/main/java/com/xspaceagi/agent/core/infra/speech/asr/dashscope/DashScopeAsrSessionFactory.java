package com.xspaceagi.agent.core.infra.speech.asr.dashscope;

import com.xspaceagi.agent.core.infra.speech.asr.AsrService;
import com.xspaceagi.agent.core.infra.speech.asr.AsrSessionFactory;
import com.xspaceagi.agent.core.infra.speech.audio.OpusDecoder;
import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.netty.http.client.HttpClient;

@Component
@ConditionalOnProperty(prefix = "speech.asr", name = "provider", havingValue = "dashscope", matchIfMissing = true)
@AllArgsConstructor
public class DashScopeAsrSessionFactory implements AsrSessionFactory {

    private final SpeechProperties speechProperties;
    private final OpusDecoder opusDecoder;
    private final HttpClient dashScopeAsrHttpClient;

    @Override
    public AsrService createSession(AudioParams audioParams) {
        return new DashScopeAsrService(speechProperties, opusDecoder, audioParams, dashScopeAsrHttpClient);
    }
}
