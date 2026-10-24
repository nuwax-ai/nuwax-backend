package com.xspaceagi.userapp.application.oauth2;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OAuth2 接入配置注册（与 AuthWebConfiguration 的写法保持一致）
 */
@Configuration
@EnableConfigurationProperties(OAuth2Properties.class)
public class OAuth2Configuration {

}
