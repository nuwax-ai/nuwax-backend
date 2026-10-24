package com.xspaceagi.im.application.config;

import com.xspaceagi.im.application.support.ImBindProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ImBindProperties.class)
public class ImBindConfiguration {
}
