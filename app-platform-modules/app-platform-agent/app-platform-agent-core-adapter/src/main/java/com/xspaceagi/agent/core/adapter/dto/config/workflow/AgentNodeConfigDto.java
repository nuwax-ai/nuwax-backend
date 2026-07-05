package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class AgentNodeConfigDto extends NodeConfigDto {

    @Schema(description = "智能体ID")
    private Long agentId;

    @Schema(description = "节点名称")
    private String name;

    @Schema(description = "节点描述")
    private String description;

    @Schema(description = "节点图标")
    private String icon;

    @Schema(description = "补充提示词")
    private String extraPrompt;

    @Schema(description = "自身循环次数")
    private int selfLoopTimes;

    @Schema(description = "循环提醒提示词")
    private String reminderPrompt;

    @Schema(description = "智能体配置信息，执行阶段使用")
    private AgentConfigDto agentConfigDto;
}
