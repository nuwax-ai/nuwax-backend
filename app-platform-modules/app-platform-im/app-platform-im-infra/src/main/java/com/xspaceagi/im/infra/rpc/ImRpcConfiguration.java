package com.xspaceagi.im.infra.rpc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IM RPC 适配装配。
 *
 * <p>★这里只注册<b>一个</b> {@link ImRpcClient} 单例：它同时实现
 * {@code IImMessageRpcService} / {@code IImConversationRpcService} / {@code IImContactRpcService} /
 * {@code IImEventRpcService}，其他模块按接口注入即可，不必感知 HTTP 与签名细节。
 *
 * <p>★装配期<b>不</b>因为「配置没填」而失败：那样会让本地起不来（IM 未部署时整个平台跟着挂）。
 * 缺配置的表现是<b>调用时</b>抛 {@code ImRpcException}（信息里写明缺哪一项），
 * 启动日志里给一条 WARN 提示。
 */
@Slf4j
@Configuration
public class ImRpcConfiguration {

    @Bean
    public ImRpcClient imRpcClient(ImRpcProperties properties) {
        if (properties.usable()) {
            log.info("IM RPC 适配已启用：{}", properties);
        } else {
            log.warn("IM RPC 适配未就绪，调用将直接失败（配置前缀 im.adapter）：{}", properties);
        }
        return new ImRpcClient(properties);
    }
}
