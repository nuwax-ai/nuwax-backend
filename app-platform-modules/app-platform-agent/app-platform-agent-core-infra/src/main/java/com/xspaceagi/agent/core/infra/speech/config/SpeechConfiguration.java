package com.xspaceagi.agent.core.infra.speech.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SpeechProperties.class)
public class SpeechConfiguration {
}
