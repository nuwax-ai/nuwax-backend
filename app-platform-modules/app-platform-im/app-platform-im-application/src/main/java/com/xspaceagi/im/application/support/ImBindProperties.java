package com.xspaceagi.im.application.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * IM 绑定相关配置。
 * <p>默认回跳为租户 {@code siteUrl + returnPath}
 * 额外允许项按「scheme + 精确 host(+port) + path 前缀」匹配，见 {@code return-url-allow-prefixes}。
 */
@Data
@ConfigurationProperties(prefix = "im.bind")
public class ImBindProperties {

    /**
     * 拼在租户 siteUrl 后的回跳路径
     */
    private String returnPath = "/api/v1/im-notify/bind/return";

    /**
     * 额外允许的 appReturnUrl（可选）。按 URI 解析：scheme 一致、host 精确匹配、path 为前缀。
     */
    private List<String> returnUrlAllowPrefixes = new ArrayList<>();

    /**
     * 是否允许 http 回跳。生产应保持 false（confirmToken 在 query）。
     */
    private boolean allowHttpReturnUrl = false;
}
