package com.xspaceagi.agent.web.ui.ws.speech;

import com.xspaceagi.agent.core.infra.speech.config.SpeechProperties;
import com.xspaceagi.agent.core.infra.speech.protocol.NaConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class AppSpeechWebSocketConfig implements WebSocketConfigurer {

    private final AppAsrWebSocketHandler appAsrWebSocketHandler;
    private final AppTtsWebSocketHandler appTtsWebSocketHandler;
    private final AppBearerHandshakeInterceptor appBearerHandshakeInterceptor;
    private final SpeechProperties speechProperties;

    public AppSpeechWebSocketConfig(AppAsrWebSocketHandler appAsrWebSocketHandler,
            AppTtsWebSocketHandler appTtsWebSocketHandler,
            AppBearerHandshakeInterceptor appBearerHandshakeInterceptor,
            SpeechProperties speechProperties) {
        this.appAsrWebSocketHandler = appAsrWebSocketHandler;
        this.appTtsWebSocketHandler = appTtsWebSocketHandler;
        this.appBearerHandshakeInterceptor = appBearerHandshakeInterceptor;
        this.speechProperties = speechProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] appOrigins = appOrigins();
        // setAllowedOriginPatterns 兼容浏览器 Origin: null（file:// / 本地模拟）
        registry.addHandler(appAsrWebSocketHandler, speechProperties.getApp().getAsrWebsocketPath())
                .addInterceptors(appBearerHandshakeInterceptor)
                .setAllowedOriginPatterns(appOrigins);
        registry.addHandler(appTtsWebSocketHandler, speechProperties.getApp().getTtsWebsocketPath())
                .addInterceptors(appBearerHandshakeInterceptor)
                .setAllowedOriginPatterns(appOrigins);
    }

    private String[] appOrigins() {
        if (speechProperties.getApp().getAllowedOrigins() == null
                || speechProperties.getApp().getAllowedOrigins().isEmpty()) {
            return new String[] {"*"};
        }
        return speechProperties.getApp().getAllowedOrigins().toArray(String[]::new);
    }

    /**
     * Tomcat 默认 WebSocket 缓冲区为 8KB，而协议允许单包最大 16KB。
     */
    @Bean
    ServletServerContainerFactoryBean appSpeechWebSocketContainer() {
        int bufferSize = NaConstants.MAX_WEBSOCKET_PAYLOAD_BYTES;
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(bufferSize);
        container.setMaxTextMessageBufferSize(bufferSize);
        return container;
    }
}
