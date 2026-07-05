package com.xspaceagi.eco.market.domain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生态市场配置属性
 * 对应 application.yml 中的 eco-market 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "eco-market")
public class EcoMarketProperties {

    /**
     * 服务端配置
     */
    private ServerConfig server = new ServerConfig();

    private WebConfig web = new WebConfig();

    /**
     * 服务端配置
     */
    @Data
    public static class ServerConfig {
        /**
         * 远程服务器基础URL
         */
        private String baseUrl;
    }

    /**
     * 服务端WEB地址配置
     */
    @Data
    public static class WebConfig {
        /**
         * 远程服务器基础URL
         */
        private String baseUrl;
    }
}