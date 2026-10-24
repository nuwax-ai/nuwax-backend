package com.xspaceagi.agent.core.infra.speech.tts.dashscope;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;
import com.xspaceagi.agent.core.infra.speech.tts.TtsService;
import com.xspaceagi.agent.core.infra.speech.tts.TtsStream;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.netty.http.client.HttpClient;

@Service
@ConditionalOnProperty(prefix = "speech.tts", name = "provider", havingValue = "dashscope", matchIfMissing = true)
@AllArgsConstructor
public class DashScopeTtsService implements TtsService {

    private final SpeechProperties speechProperties;
    private final HttpClient dashScopeTtsHttpClient;

    @Override
    public TtsStream openStream(AudioParams audioParams) {
        return new DashScopeTtsStreamSession(speechProperties, audioParams, dashScopeTtsHttpClient);
    }
}
