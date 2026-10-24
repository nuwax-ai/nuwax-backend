package com.xspaceagi.agent.web.ui.controller.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 任务列表查询参数。
 * <p>
 * spaceId 与 agentId 二选一：
 * <ul>
 *   <li>spaceId：查询指定空间下的全部任务（富化 Agent/Workflow 名称图标）</li>
 *   <li>agentId：查询当前登录用户在该智能体下创建的任务（天然隔离其他用户）</li>
 * </ul>
 */
@Data
@Schema(name = "任务列表查询参数")
public class TaskListQueryDto {

    @Schema(description = "空间ID，按空间查询任务列表（与 agentId 二选一）")
    private Long spaceId;

    @Schema(description = "智能体ID，按当前用户在某智能体下查询任务列表（与 spaceId 二选一）")
    private String agentId;
}
