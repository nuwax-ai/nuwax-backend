package com.xspaceagi.sandbox.infra.dao.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 沙盒配置值
 */
@Schema(description = "沙盒配置值")
@Data
public class SandboxConfigValue {

    @Schema(description = "服务根地址，例如 http://192.168.1.11，不允许携带端口")
    private String hostWithScheme;

    @Schema(description = "Agent服务端口")
    private int agentPort;

    @Schema(description = "VNC服务端口")
    private int vncPort;

    @Schema(description = "终端服务端口")
    private int ttydPort;

    @Schema(description = "文件服务端口")
    private int fileServerPort;

    @Schema(description = "API密钥")
    private String apiKey;

    @Schema(description = "最大用户数")
    private Integer maxUsers;
}
