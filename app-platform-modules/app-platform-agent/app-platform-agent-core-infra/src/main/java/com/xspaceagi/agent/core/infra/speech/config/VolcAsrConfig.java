package com.xspaceagi.agent.core.infra.speech.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(prefix = "speech.asr", name = "provider", havingValue = "volc")
public class VolcAsrConfig {

    @Bean
    HttpClient volcAsrHttpClient(SpeechProperties speechProperties) {
        return HttpClient.create()
                .responseTimeout(Duration.ofMillis(speechProperties.getAsr().getVolc().getResultTimeoutMs()));
    }
}
