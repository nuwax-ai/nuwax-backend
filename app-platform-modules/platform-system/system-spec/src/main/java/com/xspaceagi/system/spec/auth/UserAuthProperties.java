package com.xspaceagi.system.spec.auth;

import com.google.common.collect.Lists;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 用户鉴权相关配置
 */
@Data
@ConfigurationProperties(prefix = "auth")
public class UserAuthProperties {


    /**
     * 不需要鉴权的uri
     */
    private List<String> excludePath = Lists.newArrayList("/login", "/health", "/ready");

    /**
     * antiXSS 白名单：受限 referer 上下文（页面应用 /page/、电脑使用 /api/computer/）
     * 除各自命名空间外，额外允许访问的 API 前缀集合。
     * <p>
     * 前缀匹配：以 {@code **} 结尾按前缀放行（如 {@code /api/published/**}），否则精确/前缀匹配。
     */
    private List<String> antiXssApiWhitelist = Lists.newArrayList();

    /**
     * Sec-Fetch 元数据校验全局策略（作用于 R1 跨站收紧规则）：
     * {@code off}=关闭；{@code log}=命中仅记日志；{@code enforce}=命中直接拒绝（4030）。
     */
    private String secFetchPolicy = "enforce";

    /**
     * R2（同源 fetch/XHR 且无 Referer，no-referrer 规避探测）单独策略，
     * 取值同 {@link #secFetchPolicy}；留空则回退到全局策略。
     */
    private String secFetchNoRefererPolicy = "enforce";

    /**
     * Sec-Fetch 校验放行白名单（误伤接口配置化放行），匹配口径与 {@link #antiXssApiWhitelist} 一致。
     */
    private List<String> secFetchAllow = Lists.newArrayList();

}
