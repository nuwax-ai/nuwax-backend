package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "创建工作空间请求DTO")
public class CreateWorkspaceDto implements Serializable {

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @NotNull
    @JsonProperty("cId")
    @Schema(description = "会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long cId;

    @Schema(description = "技能ID列表")
    private List<Long> skillIds;

    @Schema(description = "子代理列表")
    private List<SubagentDto> subagents;

    @Schema(description = "MCP servers配置，key为server名称，value为server配置")
    private Map<String, McpServerConfigDto> mcpServersConfig;

    @Schema(description = "工具权限配置")
    private PermissionsConfigDto permissionsConfig;

    @Schema(description = "Hooks配置，key为事件类型(如PreToolUse)，value为Hook规则列表")
    private Map<String, List<HookEntryDto>> hooksConfig;

    @Schema(description = "Hook外挂脚本列表")
    private List<HookScriptDto> hookScripts;

}
