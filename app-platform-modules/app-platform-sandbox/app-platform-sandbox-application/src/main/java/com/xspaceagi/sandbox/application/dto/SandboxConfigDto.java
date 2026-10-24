package com.xspaceagi.sandbox.application.dto;

import com.xspaceagi.sandbox.infra.dao.vo.SandboxConfigValue;
import com.xspaceagi.sandbox.infra.dao.vo.SandboxServerInfo;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 沙盒配置 DTO
 */
@Schema(description = "沙盒配置信息")
@Data
public class SandboxConfigDto {

    @Schema(description = "配置ID")
    private Long id;

    @Schema(description = "配置范围：global-全局配置 user-个人配置")
    private SandboxScopeEnum scope;

    @Schema(description = "用户ID（scope为user时必填）")
    private Long userId;

    @Schema(description = "客户端设备ID（前端真实 deviceId）")
    private String deviceId;

    @Schema(description = "配置名称")
    private String name;

    @Schema(description = "唯一标识，用户智能体电脑连接有用")
    private String configKey;

    @Schema(description = "配置值")
    private SandboxConfigValue configValue;

    @Schema(description = "沙盒服务端信息（个人智能体电脑专有）")
    private SandboxServerInfo serverInfo;

    @Schema(description = "配置描述")
    private String description;

    @Schema(description = "是否启用：true-启用 false-禁用")
    private Boolean isActive;

    @Schema(description = "是否在线：true-在线 false-离线")
    private boolean online;

    @Schema(description = "正在使用的用户数")
    private int usingCount;

    @Schema(description = "最大并发执行Agent数量")
    private Integer maxAgentCount;

    @Schema(description = "穿透服务端地址")
    private String serverHost;

    @Schema(description = "给智能体电脑分配的agentId")
    private Long agentId;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "穿透服务端端口")
    private int serverPort;

    @Schema(description = "会话密钥，仅首次注册时有效")
    private String token;

    @Schema(description = "沙盒类型：智能体 Agent；应用开发 PageApp")
    private String type;

    @Schema(description = "绑定信息")
    private List<SandboxBindInfoDto> bindItems;

    @Schema(description = "沙盒隔离：Tenant-租户维度隔离；Space-空间维度隔离；Project-项目维度隔离")
    private String isolation;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "更新时间")
    private Date modified;
}
