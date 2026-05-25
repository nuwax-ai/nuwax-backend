package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class TryReqDto implements Serializable {

    @Schema(description = "会话唯一标识")
    @NotNull(message = "conversationId is required")
    private Long conversationId;

    @Schema(description = "变量参数，前端需要根据agent配置组装参数")
    private Map<String, Object> variableParams;

    @Schema(description = "chat消息")
    private String message;

    @Schema(description = "附件列表")
    private List<AttachmentDto> attachments;

    @Schema(description = "是否调试模式")
    private boolean debug;

    @Schema(description = "来源", hidden = true)
    private String from;

    @Schema(description = "用户选择的沙盒（我的电脑）ID")
    private Long sandboxId;

    private List<SelectedComponentDto> selectedComponents;

    @Schema(description = "前端选中的技能ID列表")
    private List<Long> skillIds;

    @Schema(description = "长期记忆中是否过滤敏感信息")
    private Boolean filterSensitive;

    @Schema(description = "用户选择的模型ID")
    private Long modelId;

    @Schema(description = "请求ID", hidden = true)
    private String requestId;

    @Data
    public static class SelectedComponentDto {
        @Schema(description = "组件ID")
        private Long id; // 组件配置ID

        @Schema(description = "组件类型")
        private AgentComponentConfig.Type type; // 组件类型，可选值：Plugin, Workflow, Trigger, Knowledge, Variable, Database
    }
}
