package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 动作执行请求（actionId 与 providerService+actionKey 二选一）。
 * <p>
 * 长度上限与 connector_action 列宽对应（provider_service 64 / action_key 128）。
 */
@Data
@Schema(description = "动作执行请求")
public class ExecuteRequestDto {

    @Schema(description = "动作 id（与 providerService+actionKey 二选一）")
    private Long actionId;

    @Size(max = 64, message = "长度不能超过 64")
    private String providerService;

    @Size(max = 128, message = "长度不能超过 128")
    private String actionKey;

    @Schema(description = "指定连接 id；缺省自动取当前用户该连接器第一个 active 连接")
    private Long connectionId;

    @Schema(description = "来源 agent id（Agent 调用时传入）")
    private Long agentId;

    @Schema(description = "输入参数")
    private Map<String, Object> args;
}
