package com.xspaceagi.im.application.config;

import com.xspaceagi.im.wechat.ilink.IlinkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * iLink HTTP 客户端 Bean（对齐 openclaw-weixin 网关调用）
 */
@Configuration
@EnableConfigurationProperties(WechatIlinkProperties.class)
public class WechatIlinkClientConfiguration {

    @Bean
    public IlinkHttpClient ilinkHttpClient(
            @Value("${wechat.ilink.sk-route-tag:}") String skRouteTag) {
        String tag = StringUtils.isBlank(skRouteTag) ? null : skRouteTag.trim();
        return new IlinkHttpClient(tag);
    }
}
