package com.xspaceagi.eco.market.domain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 生态市场配置类
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(EcoMarketProperties.class)
public class EcoMarketConfig {

    private final EcoMarketProperties ecoMarketProperties;

    public EcoMarketConfig(EcoMarketProperties ecoMarketProperties) {
        this.ecoMarketProperties = ecoMarketProperties;
        log.info("Eco-market config loaded, server URL: {}",
                ecoMarketProperties.getServer().getBaseUrl());
    }

    /**
     * 配置RestTemplate用于服务调用
     *
     * @return RestTemplate实例
     */
    @Bean
    public RestTemplate ecoMarketRestTemplate() {
        return new RestTemplate();
    }

    /**
     * 获取服务器基础URL
     *
     * @return 服务器基础URL
     */
    public String getServerBaseUrl() {
        return ecoMarketProperties.getServer().getBaseUrl();
    }
}