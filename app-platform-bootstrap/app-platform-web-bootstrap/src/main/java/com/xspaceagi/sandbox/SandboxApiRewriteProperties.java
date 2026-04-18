package com.xspaceagi.sandbox;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sandbox API URL 重写配置（全局生效）
 */
@Component
@ConfigurationProperties(prefix = "sandbox.api.rewrite")
@Getter
@Setter
public class SandboxApiRewriteProperties {

    /**
     * 允许重写的路径模式（相对于 /api/v1/4sandbox 之后的部分匹配）。
     */
    private List<String> allowPath = new ArrayList<>();
}
